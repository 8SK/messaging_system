package common;

/** One protocol message: TYPE|SENDER|RECIPIENT|CONTENT */
public class Message {
    private final String type;
    private final String sender;
    private final String recipient;
    private final String content;

    public Message(String type, String sender, String recipient, String content) {
        this.type = nz(type);
        this.sender = nz(sender);
        this.recipient = nz(recipient);
        this.content = nz(content);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public String getType()      { return type; }
    public String getSender()    { return sender; }
    public String getRecipient() { return recipient; }
    public String getContent()   { return content; }

    @Override
    public String toString() {
        return Protocol.encode(this);
    }
}
