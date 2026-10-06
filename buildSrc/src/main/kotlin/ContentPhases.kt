/**
 * The content of Mekanized, phase by phase (MEKANIZED_PLAN.md): one table instead of lists spread over
 * the import. A phase names the Mekanism content it generates and the recipe types of its machines.
 * Its [key] is the prefix of its crafting recipe files; the recipe split gives a crafting recipe to the
 * first phase (in table order) whose content, with the earlier phases', holds every item it uses.
 */
internal data class Phase(
    /** File prefix of the phase's crafting recipes (`recipes_<key>_shaped.yml`). */
    val key: String,
    /** The phase number in MEKANIZED_PLAN.md, for the headers of the generated files. */
    val planPhase: Int,
    /** CraftEngine configuration files of its machines, blocks and items. */
    val files: List<ContentFile> = listOf(),
    /** Tools, armour and gear items (their own generator). */
    val gear: List<GearItem> = listOf(),
    /** Gear modules and their ingredients. */
    val modules: List<String> = listOf(),
    /** Machine recipe types run by its machines (one definitions file each). */
    val recipeTypes: List<String> = listOf(),
)

/**
 * One generated CraftEngine configuration file of machine blocks.
 *
 * @param machines block name of every machine (or machine part) of the file
 * @param transports cables, tubes, pipes and transporters, with the low edge of their square section
 * @param items items without a block (upgrades, tablets, tools...)
 * @param plain plain blocks the structures count (no machine behind them)
 * @param extraModels items with only a model and a name key: name -> (model, name key)
 */
internal data class ContentFile(
    val fileName: String,
    val header: String,
    val machines: List<String> = listOf(),
    val transports: Map<String, Int> = mapOf(),
    val items: List<String> = listOf(),
    val plain: List<String> = listOf(),
    val extraModels: Map<String, Pair<String, String>> = mapOf(),
)

/** A gear item: the vanilla item it is built on, the slot it is worn in, whether it shows the vanilla armour. */
internal data class GearItem(
    val name: String,
    val material: String,
    val slot: String? = null,
    val vanillaLook: Boolean = false,
    val model: String? = null,
)

internal object ContentPhases {
    val tiers = listOf("basic", "advanced", "elite", "ultimate")

    /** Mekanism's factory kinds: each runs several processes of one machine at once. */
    val factoryKinds = listOf("smelting", "enriching", "crushing", "compressing", "combining", "purifying", "injecting", "infusing", "sawing")

    /** Transports of a phase: the low edge of their square section (small 5..11, large 4..12). */
    private fun transports(suffix: String, edge: Int) = tiers.associate { "${it}_$suffix" to edge }

    val all: List<Phase> = listOf(
        // Phase 1: materials, ores and storage blocks are picked by name patterns (see MekanismContent).
        Phase("materials", 1),
        Phase("machines", 2,
            files = listOf(ContentFile("machines.yml",
                "Mekanized machines, generators, energy cubes, cables and upgrades (Phase 2), generated from Mekanism (MIT).",
                machines = listOf(
                    "enrichment_chamber", "crusher", "energized_smelter", "osmium_compressor", "combiner", "metallurgic_infuser",
                    "purification_chamber", "precision_sawmill", "heat_generator", "solar_generator", "bio_generator",
                    "basic_energy_cube", "advanced_energy_cube", "elite_energy_cube", "ultimate_energy_cube", "creative_energy_cube",
                ),
                transports = transports("universal_cable", 5),
                items = listOf("upgrade_speed", "upgrade_energy", "solar_panel"))),
            recipeTypes = listOf("enriching", "crushing", "combining", "sawing", "metallurgic_infusing", "compressing", "purifying",
                // Not machines: items turned into a chemical or energy when put into a machine's slot.
                "chemical_conversion", "energy_conversion")),
        Phase("chemistry", 3,
            files = listOf(ContentFile("chemistry.yml",
                "Mekanized chemical machines, tanks, tubes, pipes and the pump (Phase 3), generated from Mekanism (MIT).",
                machines = listOf(
                    "chemical_oxidizer", "chemical_infuser", "chemical_injection_chamber", "chemical_dissolution_chamber",
                    "chemical_washer", "chemical_crystallizer", "electrolytic_separator", "rotary_condensentrator", "electric_pump",
                ) + (tiers + "creative").flatMap { listOf("${it}_chemical_tank", "${it}_fluid_tank") },
                transports = transports("pressurized_tube", 5) + transports("mechanical_pipe", 4),
                // Crafting ingredients (the tablet charges items in Phase 4).
                items = listOf("electrolytic_core", "energy_tablet"))),
            recipeTypes = listOf("injecting", "oxidizing", "dissolution", "washing", "crystallizing", "chemical_infusing", "separating",
                // Rotary recipes go both ways: split into one recipe type per direction (the machine's mode).
                "condensing", "decondensing")),
        Phase("logistics", 4,
            files = listOf(ContentFile("logistics.yml",
                "Mekanized transporters, the logistical sorter, factories, tier installers and the configurator (Phase 4), generated from Mekanism (MIT).",
                machines = listOf("logistical_sorter") + tiers.flatMap { tier -> factoryKinds.map { "${tier}_${it}_factory" } },
                transports = transports("logistical_transporter", 4),
                items = tiers.map { "${it}_tier_installer" } + "configurator"))),
        Phase("multiblocks", 5,
            files = listOf(ContentFile("multiblocks.yml",
                "Mekanized multiblocks: Dynamic Tank, Induction Matrix, Thermal Evaporation Plant (Phase 5a), generated from Mekanism (MIT).",
                // Frame blocks: machines (they find their structure and pass through to its storage).
                machines = listOf("dynamic_tank", "dynamic_valve", "structural_glass", "induction_casing", "induction_port",
                    "thermal_evaporation_block", "thermal_evaporation_valve", "thermal_evaporation_controller"),
                // Interior blocks of the induction matrix: plain blocks the structure counts.
                plain = tiers.flatMap { listOf("${it}_induction_cell", "${it}_induction_provider") })),
            // The Thermal Evaporation Plant: no energy, its rate comes from its height and the sun.
            recipeTypes = listOf("evaporating")),
        Phase("endgame", 6,
            files = listOf(
                ContentFile("endgame.yml", "Mekanized late-game machines and generators (Phase 6), generated from Mekanism (MIT).",
                    machines = listOf("pressurized_reaction_chamber", "isotopic_centrifuge", "solar_neutron_activator",
                        "antiprotonic_nucleosynthesizer", "painting_machine", "pigment_extractor", "pigment_mixer",
                        "gas_burning_generator", "wind_generator", "advanced_solar_generator")),
                // Phase 6 wave 2: storage and utility blocks.
                ContentFile("utility.yml", "Mekanized bins, personal storage, chargepad, plenisher, assemblicator, lasers (Phase 6), generated from Mekanism (MIT).",
                    machines = (tiers + "creative").map { "${it}_bin" } + listOf("personal_chest", "personal_barrel", "chargepad",
                        "fluidic_plenisher", "formulaic_assemblicator", "laser", "laser_amplifier", "laser_tractor_beam", "oredictionificator",
                        "industrial_alarm"))),
            // Painting and pigments, late-game chemistry, the Pressurized Reaction Chamber.
            recipeTypes = listOf("painting", "pigment_extracting", "pigment_mixing", "centrifuging", "activating", "nucleosynthesizing", "reaction")),
        // Phase 6 wave 3: heat, boiler, turbine, fission and fusion reactors, SPS.
        Phase("reactors", 6,
            files = listOf(ContentFile("reactors.yml",
                "Mekanized heaters, thermodynamic conductors, boiler, turbine, fission and fusion reactors, SPS (Phase 6 wave 3), generated from Mekanism (MIT).",
                // Frames, valves and ports, and the blocks inside the structures (machines too: their structure counts them).
                machines = listOf("resistive_heater", "fuelwood_heater",
                    "boiler_casing", "boiler_valve", "pressure_disperser", "superheating_element",
                    "turbine_casing", "turbine_valve", "turbine_vent", "turbine_rotor", "rotational_complex", "electromagnetic_coil", "saturating_condenser",
                    "fission_reactor_casing", "fission_reactor_port", "fission_reactor_logic_adapter", "reactor_glass", "fission_fuel_assembly", "control_rod_assembly",
                    "fusion_reactor_controller", "fusion_reactor_frame", "fusion_reactor_port", "fusion_reactor_logic_adapter", "laser_focus_matrix",
                    "sps_casing", "sps_port", "supercharged_coil"),
                transports = transports("thermodynamic_conductor", 5),
                items = listOf("turbine_blade")))),
        // Phase 6 wave 4: Digital Miner, seismic vibrator, teleporter, QIO, cardboard box.
        Phase("automation", 6,
            files = listOf(ContentFile("automation.yml",
                "Mekanized Digital Miner, seismic vibrator, teleporter, QIO and the cardboard box (Phase 6 wave 4), generated from Mekanism (MIT).",
                machines = listOf("digital_miner", "seismic_vibrator", "teleporter", "teleporter_frame",
                    "qio_drive_array", "qio_dashboard", "qio_importer", "qio_exporter", "qio_redstone_adapter"),
                items = listOf("cardboard_box", "teleportation_core", "robit", "seismic_reader", "qio_drive_base", "qio_drive_hyper_dense",
                    "qio_drive_time_dilating", "qio_drive_supermassive"),
                extraModels = mapOf("cardboard_box_storage" to ("mekanized:block/cardboard_box_storage" to "block.mekanized.cardboard_box"))))),
        // Phase 6 wave 5: tools, armour and modules (their own file, gear.yml).
        Phase("gear", 6,
            gear = listOf(
                GearItem("atomic_disassembler", "netherite_pickaxe"),
                GearItem("meka_tool", "netherite_pickaxe", model = "mekanized:item/atomic_disassembler"),
                GearItem("flamethrower", "stick"),
                GearItem("electric_bow", "bow"),
                GearItem("network_reader", "stick"),
                GearItem("canteen", "paper"),
                GearItem("portable_teleporter", "paper"),
                GearItem("portable_qio_dashboard", "paper"),
                GearItem("configuration_card", "paper"),
                GearItem("gauge_dropper", "paper"),
                GearItem("dictionary", "paper"),
                GearItem("crafting_formula", "paper"),
                GearItem("jetpack", "paper", "chest"),
                GearItem("jetpack_armored", "diamond_chestplate", "chest"),
                GearItem("scuba_tank", "paper", "chest"),
                GearItem("scuba_mask", "paper", "head"),
                GearItem("free_runners", "leather_boots", "feet"),
                GearItem("free_runners_armored", "diamond_boots", "feet"),
                GearItem("mekasuit_helmet", "netherite_helmet", vanillaLook = true),
                GearItem("mekasuit_bodyarmor", "netherite_chestplate", vanillaLook = true),
                GearItem("mekasuit_pants", "netherite_leggings", vanillaLook = true),
                GearItem("mekasuit_boots", "netherite_boots", vanillaLook = true),
                GearItem("hazmat_mask", "leather_helmet", vanillaLook = true),
                GearItem("hazmat_gown", "leather_chestplate", vanillaLook = true),
                GearItem("hazmat_pants", "leather_leggings", vanillaLook = true),
                GearItem("hazmat_boots", "leather_boots", vanillaLook = true),
            ),
            // Modules that work in Mekanized (installed by sneak-using them while wearing or holding the gear).
            modules = listOf("module_base", "module_electrolytic_breathing_unit", "module_vision_enhancement_unit",
                "module_nutritional_injection_unit", "module_inhalation_purification_unit", "module_jetpack_unit",
                "module_gravitational_modulating_unit", "module_elytra_unit", "module_locomotive_boosting_unit",
                "module_hydraulic_propulsion_unit", "module_excavation_escalation_unit", "module_attack_amplification_unit",
                "module_silk_touch_unit", "module_fortune_unit", "module_vein_mining_unit", "module_teleportation_unit",
                "module_radiation_shielding_unit", "module_geiger_unit", "module_dosimeter_unit",
                "module_energy_unit", "module_charge_distribution_unit", "module_gyroscopic_stabilization_unit", "module_solar_recharging_unit",
                "module_geothermal_generator_unit", "module_motorized_servo_unit", "module_hydrostatic_repulsor_unit",
                "module_magnetic_attraction_unit", "module_frost_walker_unit", "module_soul_surfer_unit", "module_blasting_unit",
                "module_farming_unit", "module_shearing_unit",
                // Ingredient of the elytra unit.
                "hdpe_elytra")),
        // Phase 6 wave 6: chunk loading, entangloporter, the last upgrades, tools, radiation.
        Phase("extras", 6,
            files = listOf(ContentFile("extras.yml",
                "Mekanized chunk loading, quantum entangloporter, the last upgrades and tools (Phase 6 wave 6), generated from Mekanism (MIT).",
                machines = listOf("dimensional_stabilizer", "quantum_entangloporter", "radioactive_waste_barrel",
                    "nutritional_liquifier", "modification_station"),
                items = listOf("upgrade_chemical", "upgrade_filter", "upgrade_anchor", "upgrade_stone_generator",
                    "dosimeter", "geiger_counter")))),
    )

    /** The plan phase of a machine recipe type. */
    fun planPhase(recipeType: String): Int = all.first { recipeType in it.recipeTypes }.planPhase
}
