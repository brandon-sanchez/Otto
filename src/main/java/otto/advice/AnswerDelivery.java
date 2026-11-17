package otto.advice;

/** How a checked chat answer reaches the user. */
public enum AnswerDelivery {
    /** Word for word, with no model reply written around it. */
    UNCHANGED,
    /** As a tool result the chat model explains in its own reply. */
    THROUGH_CHAT_MODEL
}
