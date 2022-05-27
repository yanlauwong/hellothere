package hellothere.service.google

import com.google.api.client.auth.oauth2.Credential
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import hellothere.dto.email.EmailDto
import hellothere.model.email.EmailFormat
import hellothere.model.email.EmailHeaderName
import hellothere.model.email.UserEmail
import hellothere.model.user.UserAccessToken
import hellothere.repository.email.UserEmailRepository
import hellothere.service.user.UserService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@Service
class GmailService(
    @Value("\${gmail.client.projectId}") private val projectId: String,
    private val userEmailRepository: UserEmailRepository,
    private val googleAuthenticationService: GoogleAuthenticationService,
    private val userService: UserService,
) {
    fun getGmailClientFromCredentials(credentials: Credential): Gmail {
        return Gmail.Builder(
            googleAuthenticationService.httpTransport,
            googleAuthenticationService.jsonFactory,
            credentials
        ).setApplicationName(projectId)
            .build()
    }

    fun getGmailClientFromUsername(username: String): Gmail? {
        return googleAuthenticationService.getUserCredentialFromUsername(username)?.let {
            getGmailClientFromCredentials(it)
        }
    }

    fun getGmailClientFromCode(code: String): Pair<Gmail, UserAccessToken> {
        val (credentials, userAccessToken) = googleAuthenticationService
            .createAndStoreCredential(code)

        return Pair(getGmailClientFromCredentials(credentials), userAccessToken)
    }

    fun getEmailIdList(client: Gmail, query: String? = null): List<String> {
        LOGGER.info("Fetching email ids with search query: {$query}")

        val messageList = client
            .users()
            .messages()
            .list(USER_SELF_ACCESS)
            .setMaxResults(5)

        if (query != null) {
            messageList.q = query
        }

        return messageList
            .execute()
            .messages.map { it.id }
    }

    fun getEmailsBaseData(client: Gmail, username: String, search: String? = null): List<EmailDto> {
        val ids = getEmailIdList(client, search)
        val emails = getEmailsBaseData(client, ids, username)
        return emails.map { buildEmailDto(it) }
    }

    fun getEmailsBaseData(client: Gmail, gmailIds: List<String>, username: String): List<UserEmail> {
        LOGGER.info("Fetching full emails for user: $username")

        val cachedEmails = userEmailRepository.findAllByUserIdAndGmailIdIn(username, gmailIds)

        val emailIdsToFetch = mutableListOf<String>()
        emailIdsToFetch.addAll(gmailIds)
        cachedEmails.forEach { emailIdsToFetch.remove(it.gmailId) }
        // todo explore client.batch() in beta version
        // will require you to build your own http request

        val newMessages = emailIdsToFetch.map {
            client
                .users()
                .messages()
                .get(USER_SELF_ACCESS, it)
                .setFormat(EmailFormat.METADATA.value)
                .execute()
        }.toMutableList()

        val newEmails = saveNewEmailsFromGmail(newMessages, username)

        return newEmails + cachedEmails
    }

    fun saveNewEmailsFromGmail(messages: List<Message>, username: String): List<UserEmail> {
        if (messages.isEmpty()) {
            LOGGER.info("No messages to save for user $username. Email cache up to date :)")
            return listOf()
        }

        val user = userService.getUserById(username)

        if (user == null) {
            LOGGER.error("No user found to save messages for.")
            return listOf()
        }

        val emailsToSave = messages.map {
            UserEmail(
                null,
                it.id,
                getEmailHeader(it, EmailHeaderName.SUBJECT),
                getEmailHeader(it, EmailHeaderName.FROM),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(it.internalDate), ZoneId.systemDefault()),
                it.labelIds.joinToString(","),
                user
            )
        }

        return userEmailRepository.saveAll(emailsToSave)
    }

    fun getEmailHeader(message: Message, headerName: EmailHeaderName): String {
        return message.payload
            .headers
            .firstOrNull { it.name.lowercase() == headerName.value.lowercase() }
            ?.value ?: ""
    }

    fun buildEmailDto(message: Message): EmailDto {
        return EmailDto(
            message.id,
            getEmailHeader(message, EmailHeaderName.FROM),
            LocalDateTime.ofEpochSecond(message.internalDate, 0, ZoneOffset.UTC),
            message.labelIds,
            getEmailHeader(message, EmailHeaderName.SUBJECT),
            message.snippet
        )
    }

    fun buildEmailDto(userEmail: UserEmail): EmailDto {
        return EmailDto(
            userEmail.gmailId,
            userEmail.fromEmail,
            userEmail.dateSent,
            userEmail.getLabelList(),
            userEmail.subject,
            null
        )
    }

    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(GmailService::class.java)
        const val USER_SELF_ACCESS = "me"
    }
}
