package dev.buhanzaz.rwms.logistics.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData.Kind;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderReceiptData.Line;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RentalOrderReceiptDataTest {
  private static final UUID CABIN = UUID.randomUUID();
  private static final String MAX = Long.toString(Long.MAX_VALUE);

  @Test
  void retainsProductsAndTotalLargerThanLongWithoutRounding() {
    String cabinAmount =
        BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(120)).toString();
    String furnitureAmount =
        new BigInteger(MAX).pow(2).multiply(BigInteger.valueOf(120)).toString();
    var lines =
        List.of(
            new Line(Kind.CABIN, CABIN, null, "Бытовка № 1", "1", 120L, MAX, cabinAmount, 1L),
            new Line(
                Kind.FURNITURE,
                CABIN,
                UUID.randomUUID(),
                "Стул",
                MAX,
                120L,
                MAX,
                furnitureAmount,
                2L));
    String total = new BigInteger(cabinAmount).add(new BigInteger(furnitureAmount)).toString();
    assertThat(receipt(false, lines, total).totalRubles()).isEqualTo(total);
  }

  @Test
  void explicitZeroDeliveryIsNotAnUnquotedDelivery() {
    Line cabin = cabin();
    assertThat(receipt(false, List.of(cabin), "20").deliveryIncluded()).isFalse();
    Line delivery = new Line(Kind.DELIVERY, null, null, "Доставка", "1", null, "0", "0", null);
    assertThat(receipt(true, List.of(cabin, delivery), "20").deliveryIncluded()).isTrue();
    assertThatThrownBy(() -> receipt(true, List.of(cabin), "20"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsRoundedOrInconsistentMoneyAndMissingPrice() {
    for (String value : List.of("20.0", "020", "2e1", "-20", "21")) {
      assertThatThrownBy(() -> receipt(false, List.of(cabin()), value))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new Line(Kind.CABIN, CABIN, null, "Бытовка", "1", 2L, "10", "21", 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Line(Kind.CABIN, CABIN, null, "Бытовка", "1", 2L, null, "0", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void freezesLineCollectionAndRequiresPositiveQuantitiesAndRealRentalMonths() {
    var mutable = new ArrayList<>(List.of(cabin()));
    var receipt = receipt(false, mutable, "20");
    mutable.clear();
    assertThat(receipt.lines()).hasSize(1);
    assertThatThrownBy(() -> receipt.lines().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                new Line(Kind.FURNITURE, CABIN, UUID.randomUUID(), "Стул", "0", 2L, "10", "0", 1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Line(Kind.CABIN, CABIN, null, "Бытовка", "1", 121L, "10", "1210", 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Line cabin() {
    return new Line(Kind.CABIN, CABIN, null, "Бытовка № 1", "1", 2L, "10", "20", 1L);
  }

  private static RentalOrderReceiptData receipt(boolean delivery, List<Line> lines, String total) {
    return new RentalOrderReceiptData(
        1, UUID.randomUUID(), "ORD-1", OffsetDateTime.now(), "RUB", delivery, lines, total);
  }
}
