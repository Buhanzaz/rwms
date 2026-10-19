package dev.buhanzaz.rwms.analytics.fixture;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/** Groups negative fixtures for every mutating mapping forbidden on a read model. */
public final class UnsafeAnalyticsWriteControllers {
  private UnsafeAnalyticsWriteControllers() {}

  /** Unsafe read-model controller with a POST mapping. */
  @RestController
  public static final class PostController {
    @PostMapping("/architecture-fixture/analytics")
    public void create() {}
  }

  /** Unsafe read-model controller with a PUT mapping. */
  @RestController
  public static final class PutController {
    @PutMapping("/architecture-fixture/analytics")
    public void replace() {}
  }

  /** Unsafe read-model controller with a PATCH mapping. */
  @RestController
  public static final class PatchController {
    @PatchMapping("/architecture-fixture/analytics")
    public void update() {}
  }

  /** Unsafe read-model controller with a DELETE mapping. */
  @RestController
  public static final class DeleteController {
    @DeleteMapping("/architecture-fixture/analytics")
    public void delete() {}
  }

  /** Unsafe read-model controller that hides POST in a generic class-level mapping. */
  @RestController
  @RequestMapping(path = "/architecture-fixture/analytics", method = RequestMethod.POST)
  public static final class GenericPostController {
    @RequestMapping
    public void create() {}
  }

  /** Unsafe read-model controller whose method accepts every verb through a bare mapping. */
  @RestController
  public static final class BareRequestMappingController {
    @RequestMapping("/architecture-fixture/analytics")
    public void handleAnyVerb() {}
  }
}
