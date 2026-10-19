package dev.buhanzaz.rwms.asset;

import java.util.List;
import java.util.UUID;

/** Stable catalog identifiers seeded by V19 for service tests. */
public final class CabinCompositionTestIds {
  public static final UUID TYPE_BK_1 =
      UUID.fromString("af57f2b0-3a71-4b7f-8d2f-000000000001");
  public static final UUID DIMENSION_24_X_6 =
      UUID.fromString("af57f2b0-3a71-4b7f-8d2f-000000000107");
  public static final UUID FINISHING_DVP =
      UUID.fromString("af57f2b0-3a71-4b7f-8d2f-000000000201");
  public static final UUID CHARACTERISTIC_PLASTIC_WINDOW =
      UUID.fromString("af57f2b0-3a71-4b7f-8d2f-000000000301");
  public static final UUID CHARACTERISTIC_ELECTRICS_KK =
      UUID.fromString("af57f2b0-3a71-4b7f-8d2f-000000000302");
  public static final String CATEGORY_NEW = "Новая";
  public static final String CATEGORY_ORDINARY = "Обычная";

  private CabinCompositionTestIds() {}

  public static List<UUID> plasticWindow() {
    return List.of(CHARACTERISTIC_PLASTIC_WINDOW);
  }
}
