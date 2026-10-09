package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.world.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CableArmsElementTest {

    @Test
    void turnsTheArmToEachSide() {
        for (Direction direction : Direction.values()) {
            // The display draws the north arm pointing south (+Z).
            Vector3f arm = CableArmsElement.turn(direction).transform(new Vector3f(0, 0, 1));
            assertEquals(direction.dx(), arm.x, 1e-6, direction + " x");
            assertEquals(direction.dy(), arm.y, 1e-6, direction + " y");
            assertEquals(direction.dz(), arm.z, 1e-6, direction + " z");
        }
    }
}
