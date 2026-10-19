package dev.buhanzaz.rwms.assistant.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the tool executor as a bounded audit/dispatch facade rather than a workflow owner. */
class AssistantGodClassSourcePolicyTest {
  @Test
  void toolExecutorDelegatesSearchReferenceAndSelectionWorkflows() throws IOException {
    Path source =
        Path.of(
            "src/main/java/dev/buhanzaz/rwms/assistant/service/AssistantToolExecutor.java");
    String text = Files.readString(source);

    assertThat(Files.readAllLines(source)).hasSizeLessThanOrEqualTo(400);
    assertThat(text)
        .contains(
            "AssistantCabinSearchTool cabinSearch",
            "AssistantCabinReferenceTool cabinReference",
            "AssistantSelectionService selections")
        .doesNotContain(
            "prepareSearch(",
            "parseSearch(",
            "allocateSharedTotal(",
            "lookupCabinCatalog(",
            "replaceCabinSelection(");
  }
}
