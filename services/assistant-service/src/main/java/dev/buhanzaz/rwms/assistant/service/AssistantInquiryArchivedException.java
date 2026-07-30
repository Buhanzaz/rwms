package dev.buhanzaz.rwms.assistant.service;

/** A safe, terminal lifecycle signal from logistics; it is not an outage. */
public final class AssistantInquiryArchivedException extends AssistantUpstreamException {
  public AssistantInquiryArchivedException() {
    super("The rental inquiry is archived");
  }
}
