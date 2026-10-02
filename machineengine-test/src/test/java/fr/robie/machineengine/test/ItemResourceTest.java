package fr.robie.machineengine.test;

import fr.robie.machineengine.api.item.ItemData;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.transfer.storage.SingleVariantStorage;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemResourceTest {
    private static final ResourceVariant IRON = Items.variant(new ItemData(Key.key("minecraft", "iron_ingot"), new byte[]{1, 2, 3}));
    private static final ResourceVariant SAME_IRON = Items.variant(new ItemData(Key.key("minecraft", "iron_ingot"), new byte[]{1, 2, 3}));
    private static final ResourceVariant NAMED_IRON = Items.variant(new ItemData(Key.key("minecraft", "iron_ingot"), new byte[]{1, 2, 4}));

    private final CoreMachineEngine engine = TestEngine.builder().start();

    @Test
    void itemsAreBuiltInAndStackOnlyWhenIdentical() {
        assertTrue(this.engine.registries().registry(fr.robie.machineengine.api.registry.Registries.RESOURCE_TYPE).containsValue(Items.ITEM));
        assertEquals(IRON, SAME_IRON);
        SingleVariantStorage slot = SingleVariantStorage.of(64);
        try (Transaction transaction = this.engine.transactions().openOuter()) {
            assertEquals(10, slot.insert(IRON, 10, transaction));
            assertEquals(5, slot.insert(SAME_IRON, 5, transaction));
            assertEquals(0, slot.insert(NAMED_IRON, 5, transaction), "a renamed ingot is a different item");
            transaction.commit();
        }
        assertEquals(15, slot.amount());
    }

    @Test
    void itemPredicatesMatchByIdAndItemsRoundTrip() {
        assertTrue(Items.ofItem(Key.key("minecraft", "iron_ingot")).test(NAMED_IRON, ResourceTags.EMPTY));
        assertFalse(Items.ofItem(Key.key("minecraft", "gold_ingot")).test(IRON, ResourceTags.EMPTY));
        ResourceStack stack = IRON.stack(42);
        assertEquals(stack, this.engine.resourceCodecs().stack().decode(this.engine.resourceCodecs().stack().encode(stack)).value());
    }
}
