package dev.buhanzaz.rwms.taskboard.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Contact catalog transport; companies never carry route dates or authoritative assignments. */
public final class ContractorCompanyApiModels {
  private ContractorCompanyApiModels() {}

  /** Creates one city-owned contact using a stable identity for safe retries. */
  public record CreateContractorCompanyRequest(
      @NotNull UUID companyId,
      @NotBlank @Size(max = 256) String name,
      @NotBlank @Pattern(regexp = "[0-9]{10}|[0-9]{12}") String inn,
      @Size(max = 256) String contactName,
      @NotBlank @Size(max = 64) String phone,
      @Email @Size(max = 256) String email,
      @Size(max = 1000) String address,
      @Size(max = 2000) String comment) {}

  /** Replaces the observed company's contact details while retaining its city. */
  public record UpdateContractorCompanyRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 256) String name,
      @NotBlank @Pattern(regexp = "[0-9]{10}|[0-9]{12}") String inn,
      @Size(max = 256) String contactName,
      @NotBlank @Size(max = 64) String phone,
      @Email @Size(max = 256) String email,
      @Size(max = 1000) String address,
      @Size(max = 2000) String comment) {}

  /** Current contact details and immutable ownership, without any worker authentication data. */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record ContractorCompanyResponse(
      UUID companyId,
      long version,
      UUID homeWarehouseId,
      String name,
      String inn,
      String contactName,
      String phone,
      String email,
      String address,
      String comment) {}
}
