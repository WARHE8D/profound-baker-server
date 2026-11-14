package com.sugarcrumbs.catalog.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Item's invariants match the catalog schema's CHECK constraints, so a bad
 * value is a 400 from the entity rather than a constraint error from the
 * database.
 */
class ItemTests {

    private static Item.Builder valid() {
        return Item.builder()
                .name("Sourdough")
                .price(new BigDecimal("6.50"))
                .category(mock(Category.class));
    }

    @Nested
    class Creation {

        @Test
        void defaultsToAvailableSameDayAndUnlimitedStock() {
            Item item = valid().build();

            assertThat(item.isAvailable()).isTrue();
            assertThat(item.getLeadTimeHours()).isZero();
            assertThat(item.getStockQuantity()).isNull();
            assertThat(item.getImageUrls()).isEmpty();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        void requiresAName(String name) {
            assertThatIllegalArgumentException().isThrownBy(() -> valid().name(name).build());
        }

        @Test
        void requiresACategory() {
            assertThatThrownBy(() -> valid().category(null).build()).isInstanceOf(NullPointerException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "0.001", "0.004"})
        void rejectsAPriceThatIsNotPositiveOnceRoundedToCents(String price) {
            assertThatIllegalArgumentException().isThrownBy(() -> valid().price(new BigDecimal(price)).build());
        }

        @Test
        void requiresAPrice() {
            assertThatIllegalArgumentException().isThrownBy(() -> valid().price(null).build());
        }

        @Test
        void roundsThePriceToCentsHalfUp() {
            assertThat(valid().price(new BigDecimal("4.005")).build().getPrice()).isEqualByComparingTo("4.01");
            assertThat(valid().price(new BigDecimal("4")).build().getPrice()).hasToString("4.00");
        }

        @Test
        void rejectsANegativeLeadTime() {
            assertThatIllegalArgumentException().isThrownBy(() -> valid().leadTimeHours(-1).build());
        }

        @Test
        void rejectsNegativeStock() {
            assertThatIllegalArgumentException().isThrownBy(() -> valid().stockQuantity(-1).build());
        }

        @Test
        void copiesTheImageListSoTheCallerCannotChangeIt() {
            List<String> urls = new ArrayList<>(List.of("/uploads/items/a.png"));
            Item item = valid().imageUrls(urls).build();

            urls.add("/uploads/items/b.png");

            assertThat(item.getImageUrls()).containsExactly("/uploads/items/a.png");
            assertThatThrownBy(() -> item.getImageUrls().add("x")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class Pricing {

        @Test
        void repriceRoundsToCents() {
            Item item = valid().build();

            item.reprice(new BigDecimal("7.499"));

            assertThat(item.getPrice()).hasToString("7.50");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-3", "0.001"})
        void repriceRejectsAPriceThatIsNotPositive(String price) {
            Item item = valid().build();

            assertThatIllegalArgumentException().isThrownBy(() -> item.reprice(new BigDecimal(price)));
            assertThat(item.getPrice()).isEqualByComparingTo("6.50");
        }

        @Test
        void repriceRejectsNull() {
            assertThatIllegalArgumentException().isThrownBy(() -> valid().build().reprice(null));
        }
    }

    @Nested
    class Stock {

        @Test
        void adjustStockAddsAndRemoves() {
            Item item = valid().stockQuantity(5).build();

            item.adjustStock(-5);
            assertThat(item.getStockQuantity()).isZero();

            item.adjustStock(3);
            assertThat(item.getStockQuantity()).isEqualTo(3);
        }

        @Test
        void adjustStockNeverGoesNegative() {
            Item item = valid().stockQuantity(2).build();

            assertThatIllegalArgumentException().isThrownBy(() -> item.adjustStock(-3));
            assertThat(item.getStockQuantity()).isEqualTo(2);
        }

        @Test
        void adjustStockIsRefusedForUntrackedStock() {
            Item item = valid().build();

            assertThatIllegalStateException().isThrownBy(() -> item.adjustStock(-1));
            assertThat(item.getStockQuantity()).isNull();
        }

        @Test
        void updateStockQuantityCanStartOrStopTracking() {
            Item item = valid().build();

            item.updateStockQuantity(4);
            assertThat(item.getStockQuantity()).isEqualTo(4);

            item.updateStockQuantity(null);
            assertThat(item.getStockQuantity()).isNull();
        }

        @Test
        void updateStockQuantityRejectsNegative() {
            Item item = valid().stockQuantity(4).build();

            assertThatIllegalArgumentException().isThrownBy(() -> item.updateStockQuantity(-1));
            assertThat(item.getStockQuantity()).isEqualTo(4);
        }
    }

    @Nested
    class Orderability {

        @Test
        void orderableWhenAvailableAndInStockOrUntracked() {
            assertThat(valid().build().isOrderable()).isTrue();
            assertThat(valid().stockQuantity(1).build().isOrderable()).isTrue();
        }

        @Test
        void notOrderableWhenOutOfStock() {
            assertThat(valid().stockQuantity(0).build().isOrderable()).isFalse();
        }

        @Test
        void notOrderableWhenMarkedUnavailable() {
            Item item = valid().build();

            item.markUnavailable();
            assertThat(item.isOrderable()).isFalse();

            item.markAvailable();
            assertThat(item.isOrderable()).isTrue();
        }
    }

    @Nested
    class Details {

        @Test
        void renameRejectsBlank() {
            Item item = valid().build();

            assertThatIllegalArgumentException().isThrownBy(() -> item.rename(" "));
            assertThat(item.getName()).isEqualTo("Sourdough");
        }

        @Test
        void changeLeadTimeRejectsNegative() {
            Item item = valid().leadTimeHours(24).build();

            assertThatIllegalArgumentException().isThrownBy(() -> item.changeLeadTime(-1));
            assertThat(item.getLeadTimeHours()).isEqualTo(24);
        }

        @Test
        void moveToCategoryRequiresOne() {
            assertThatThrownBy(() -> valid().build().moveToCategory(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void addImageRejectsBlankAndRemoveReportsWhetherItWasThere() {
            Item item = valid().build();

            assertThatIllegalArgumentException().isThrownBy(() -> item.addImage(""));

            item.addImage("/uploads/items/a.png");
            assertThat(item.removeImage("/uploads/items/a.png")).isTrue();
            assertThat(item.removeImage("/uploads/items/a.png")).isFalse();
        }
    }
}
