package hellothere.requests.email

// todo add in mutliple email to, cc, attachments and labels
data class SendRequest(
    val to: List<String>,
    val subject: String,
    val body: String
)
