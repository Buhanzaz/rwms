package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ContractorCompanyApiModels.*;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.ContractorCompany;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.mapper.ContractorCompanyMapper;
import dev.buhanzaz.rwms.taskboard.repository.ContractorCompanyRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns hired-company contacts and city membership; driver services retain trip execution. */
@Service
@RequiredArgsConstructor
public class ContractorCompanyService {
  private final ContractorCompanyRepository companies;
  private final WorkerRepository workers;
  private final ContractorCompanyMapper mapper;
  private final TaskBoardProjectionWriter writer;
  private final JdbcTemplate jdbc;

  @Transactional(readOnly = true)
  public List<ContractorCompanyResponse> list(UUID warehouseId) {
    return companies.findAllByWarehouseIdOrderByNameAsc(warehouseId).stream()
        .map(mapper::response)
        .toList();
  }

  /** Serializes matching city/INN creates and returns only an identical stable-ID replay. */
  @Transactional
  public ContractorCompanyResponse create(
      UUID warehouseId, CreateContractorCompanyRequest request) {
    var candidate = new ContractorCompany(request.companyId(), warehouseId);
    candidate.replaceDetails(
        request.name(),
        request.inn(),
        request.contactName(),
        request.phone(),
        request.email(),
        request.address(),
        request.comment());
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "contractor-company-create:" + request.companyId());
    lockInn(warehouseId, candidate.getInn());
    var existing = companies.findByIdForUpdate(request.companyId()).orElse(null);
    if (existing != null) {
      if (!existing.sameDetails(candidate)) {
        throw new ConflictException("Идентификатор компании уже использован с другими данными");
      }
      return mapper.response(existing);
    }
    requireUniqueInn(warehouseId, candidate.getId(), candidate.getInn());
    return mapper.response(writer.saveAndFlush(companies, candidate));
  }

  @Transactional
  public ContractorCompanyResponse update(
      UUID warehouseId, UUID companyId, UpdateContractorCompanyRequest request) {
    // Match create's INN-before-company lock order.
    lockInn(warehouseId, request.inn().trim());
    var company = requireOwnedForUpdate(warehouseId, companyId);
    checkVersion(company.getVersion(), request.expectedVersion(), "Наёмная компания");
    requireUniqueInn(warehouseId, companyId, request.inn().trim());
    company.replaceDetails(
        request.name(),
        request.inn(),
        request.contactName(),
        request.phone(),
        request.email(),
        request.address(),
        request.comment());
    return mapper.response(writer.saveAndFlush(companies, company));
  }

  /** Deletes an empty company only; drivers and their assignment history are never cascaded. */
  @Transactional
  public void delete(UUID warehouseId, UUID companyId, long expectedVersion) {
    var company = requireOwnedForUpdate(warehouseId, companyId);
    checkVersion(company.getVersion(), expectedVersion, "Наёмная компания");
    if (workers.existsByContractorCompanyId(companyId)) {
      throw new ConflictException(
          "В компании есть водители. Сначала измените их принадлежность к компании");
    }
    writer.delete(companies, company);
    writer.flush();
  }

  /** Holds the company through a driver membership write, fencing concurrent company deletion. */
  @Transactional(propagation = Propagation.MANDATORY)
  public ContractorCompany requireOwnedForUpdate(UUID warehouseId, UUID companyId) {
    var company =
        companies
            .findByIdForUpdate(companyId)
            .orElseThrow(() -> new NotFoundException("Наёмная компания не найдена"));
    if (!company.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Компания не принадлежит выбранному городу");
    }
    return company;
  }

  private void requireUniqueInn(UUID warehouseId, UUID companyId, String inn) {
    companies
        .findByWarehouseIdAndInn(warehouseId, inn)
        .filter(existing -> !existing.getId().equals(companyId))
        .ifPresent(
            existing -> {
              throw new ConflictException("Компания с этим ИНН уже добавлена в выбранном городе");
            });
  }

  private void lockInn(UUID warehouseId, String inn) {
    jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        Object.class,
        "contractor-company-inn:" + warehouseId + ":" + inn);
  }
}
