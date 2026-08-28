package liangping.multirandomoneblock;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class MultiRandomOneBlockMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "multirandomoneblock";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Random RANDOM = new Random();
    private static final int CHUNK_SIZE = 16;
    private static final int MIN_CHUNK_DISTANCE = 1;

    private int playerCount = -1;
    private boolean initialized = false;
    private boolean caveMode = false;
    private int totalBrokenCount = 0;
    private List<BlockPos> overworldPlatforms = new ArrayList<>();
    private List<BlockPos> netherPlatforms = new ArrayList<>();
    private Set<BlockPos> allPlatformPositions = new HashSet<>();
    private Set<BlockPos> originalPlatformBlocks = new HashSet<>();
    private Set<UUID> receivedItemsPlayers = new HashSet<>();
    private Path dataFile;
    private Path itemsFile;

    private static final List<ItemStack> STARTING_ITEMS = List.of(
        new ItemStack(Items.LAVA_BUCKET, 5),
        new ItemStack(Items.WATER_BUCKET, 5),
        new ItemStack(Items.OAK_SAPLING, 3),
        new ItemStack(Items.RED_MUSHROOM, 1),
        new ItemStack(Items.BROWN_MUSHROOM, 1),
        new ItemStack(Items.POTATO, 2),
        new ItemStack(Items.WHEAT_SEEDS, 2),
        new ItemStack(Items.CARROT, 2),
        new ItemStack(Items.SUGAR_CANE, 2),
        new ItemStack(Items.BAMBOO, 2),
        new ItemStack(Items.END_PORTAL_FRAME, 12)
    );

    @Override
    public void onInitializeServer() {
        LOGGER.info("Multi Random One Block Mod initialized!");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            SetMemberCommand.register(dispatcher, this);
            CraftModeCommand.register(dispatcher, this);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (!receivedItemsPlayers.contains(player.getUUID())) {
                giveStartingItems(player);
                receivedItemsPlayers.add(player.getUUID());
                saveItemsData();
            }
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide() && allPlatformPositions.contains(pos)) {
                onBlockBroken((ServerLevel) world, pos, (ServerPlayer) player);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!initialized) {
                loadOrInitialize(server);
            }
            for (BlockPos platform : originalPlatformBlocks) {
                ServerLevel level = server.overworld();
                AABB checkArea = new AABB(
                    platform.getX() - 2, platform.getY() - 5, platform.getZ() - 2,
                    platform.getX() + 3, platform.getY() + 5, platform.getZ() + 3
                );
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, checkArea)) {
                    checkAndTeleportEntity(entity, platform, level);
                }
            }
        });
    }

    public void setPlayerCount(int count, MinecraftServer server) {
        if (initialized) return;
        if (count < 1) return;
        this.playerCount = count;
        this.initialized = true;
        saveData();
        
        if (server != null) {
            generateAllPlatforms(server);
        }
    }

    public boolean isInitialized() { return initialized; }
    public int getPlayerCount() { return playerCount; }
    public boolean isCaveMode() { return caveMode; }

    public void setCaveMode(boolean caveMode) {
        this.caveMode = caveMode;
        saveData();
    }

    public int getTotalBrokenCount() { return totalBrokenCount; }
    public int getRequiredCountForCaveMode() { return playerCount * 100; }

    public boolean canEnterCaveMode() {
        return totalBrokenCount >= getRequiredCountForCaveMode();
    }

    private void loadOrInitialize(MinecraftServer server) {
        dataFile = server.getWorldPath(LevelResource.ROOT).resolve("multirandomoneblock.dat");
        itemsFile = server.getWorldPath(LevelResource.ROOT).resolve("multirandomoneblock_items.dat");
        
        if (Files.exists(dataFile)) {
            try {
                List<String> lines = Files.readAllLines(dataFile);
                if (lines.size() >= 3) {
                    playerCount = Integer.parseInt(lines.get(0).trim());
                    caveMode = Boolean.parseBoolean(lines.get(1).trim());
                    totalBrokenCount = Integer.parseInt(lines.get(2).trim());
                    initialized = true;
                    LOGGER.info("Loaded data: players={}, caveMode={}, broken={}", playerCount, caveMode, totalBrokenCount);
                }
            } catch (IOException | NumberFormatException e) {
                LOGGER.error("Failed to load data", e);
            }
        }

        if (Files.exists(itemsFile)) {
            try {
                List<String> lines = Files.readAllLines(itemsFile);
                for (String line : lines) {
                    try {
                        receivedItemsPlayers.add(UUID.fromString(line.trim()));
                    } catch (IllegalArgumentException ignored) {}
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load items data", e);
            }
        }

        if (initialized) {
            generateAllPlatforms(server);
        }
    }

    private void saveData() {
        if (dataFile == null) return;
        try {
            String content = playerCount + "\n" + caveMode + "\n" + totalBrokenCount;
            Files.write(dataFile, content.getBytes());
        } catch (IOException e) {
            LOGGER.error("Failed to save data", e);
        }
    }

    private void saveItemsData() {
        if (itemsFile == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (UUID uuid : receivedItemsPlayers) {
                sb.append(uuid.toString()).append("\n");
            }
            Files.write(itemsFile, sb.toString().getBytes());
        } catch (IOException e) {
            LOGGER.error("Failed to save items data", e);
        }
    }

    private void generateAllPlatforms(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        BlockPos overworldSpawn = overworld.getSharedSpawnPos();
        overworldPlatforms = generatePlatformsForDimension(overworld, overworldSpawn, Blocks.GRASS_BLOCK);

        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether != null) {
            BlockPos netherSpawn = new BlockPos(0, 70, 0);
            netherPlatforms = generatePlatformsForDimension(nether, netherSpawn, Blocks.NETHERRACK);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal("§a[多人随机单方块] 已生成 " + playerCount + " 个平台！"));
            player.sendSystemMessage(Component.literal("§e主世界和下界各有 " + playerCount + " 个独立单方块平台"));
            player.sendSystemMessage(Component.literal("§7挖掘 " + getRequiredCountForCaveMode() + " 个方块后可开启矿洞模式"));
        }
    }

    private List<BlockPos> generatePlatformsForDimension(ServerLevel world, BlockPos center, Block baseBlock) {
        List<BlockPos> platforms = new ArrayList<>();
        
        generateSingleBlock(world, center, baseBlock);
        platforms.add(center);

        for (int i = 1; i < playerCount; i++) {
            BlockPos newPos = findValidPosition(world, center);
            if (newPos != null) {
                generateSingleBlock(world, newPos, baseBlock);
                platforms.add(newPos);
                LOGGER.info("Generated platform {} in {} at {}", i, world.dimension().location(), newPos);
            }
        }
        return platforms;
    }

    private void generateSingleBlock(ServerLevel world, BlockPos pos, Block baseBlock) {
        world.setBlock(pos, baseBlock.defaultBlockState(), 3);
        allPlatformPositions.add(pos);
        originalPlatformBlocks.add(pos);
    }

    private BlockPos findValidPosition(ServerLevel world, BlockPos center) {
        int attempts = 0;
        while (attempts < 100) {
            int distance = (MIN_CHUNK_DISTANCE + RANDOM.nextInt(5)) * CHUNK_SIZE;
            int angle = RANDOM.nextInt(360);
            int x = center.getX() + (int) (distance * Math.cos(Math.toRadians(angle)));
            int z = center.getZ() + (int) (distance * Math.sin(Math.toRadians(angle)));
            int y = center.getY();
            BlockPos pos = new BlockPos(x, y, z);

            boolean tooClose = false;
            for (BlockPos existing : allPlatformPositions) {
                if (existing.distSqr(pos) < (MIN_CHUNK_DISTANCE * CHUNK_SIZE) * (MIN_CHUNK_DISTANCE * CHUNK_SIZE)) {
                    tooClose = true;
                    break;
                }
            }

            if (!tooClose) {
                return pos;
            }
            attempts++;
        }
        return null;
    }

    private void onBlockBroken(ServerLevel world, BlockPos pos, ServerPlayer player) {
        totalBrokenCount++;
        saveData();

        Block randomBlock = getRandomBlockForDimension(world);
        world.setBlock(pos, randomBlock.defaultBlockState(), 3);

        if (totalBrokenCount % 64 == 0) {
            spawnRandomMobForDimension(world, pos.above());
        }

        if (totalBrokenCount == getRequiredCountForCaveMode()) {
            for (ServerPlayer p : world.getServer().getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.literal("§6[多人随机单方块] 已达到 " + getRequiredCountForCaveMode() + " 个挖掘数！"));
                p.sendSystemMessage(Component.literal("§e房主可输入 /craftmode cave 进入矿洞模式"));
            }
        }
    }

    private void checkAndTeleportEntity(LivingEntity entity, BlockPos platform, ServerLevel level) {
        BlockPos entityPos = entity.blockPosition();
        if (entityPos.getX() == platform.getX() && entityPos.getZ() == platform.getZ()) {
            double entityY = entity.getY();
            
            if (entityY < platform.getY() + 0.5) {
                entity.teleportTo(
                    platform.getX() + 0.5,
                    platform.getY() + 1.0,
                    platform.getZ() + 0.5
                );
                entity.setDeltaMovement(0, 0, 0);
                entity.fallDistance = 0;
                if (entity instanceof ServerPlayer player) {
                    player.sendSystemMessage(Component.literal("§c[防掉落] 已将你传送回平台"));
                }
            }
        }
    }

    private Block getRandomBlockForDimension(ServerLevel world) {
        ResourceKey<Level> dimension = world.dimension();
        List<Block> candidates = new ArrayList<>();

        if (dimension == Level.OVERWORLD) {
            if (caveMode) {
                candidates.addAll(List.of(
                    Blocks.STONE, Blocks.COBBLESTONE, Blocks.COAL_ORE, Blocks.IRON_ORE,
                    Blocks.COPPER_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE,
                    Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
                    Blocks.DEEPSLATE, Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_IRON_ORE,
                    Blocks.DEEPSLATE_COPPER_ORE, Blocks.DEEPSLATE_GOLD_ORE,
                    Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
                    Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
                    Blocks.GRANITE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.TUFF,
                    Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE,
                    Blocks.RAW_IRON_BLOCK, Blocks.RAW_COPPER_BLOCK, Blocks.RAW_GOLD_BLOCK
                ));
            } else {
                for (Block block : BuiltInRegistries.BLOCK) {
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                    if (id != null && id.getNamespace().equals("minecraft")) {
                        String path = id.getPath();
                        if (!path.contains("nether") && !path.contains("soul") &&
                            !path.contains("warped") && !path.contains("crimson") &&
                            !path.contains("blackstone") && !path.contains("basalt") &&
                            !path.contains("end") && !path.contains("chorus") &&
                            !path.contains("purpur") && !path.contains("obsidian") &&
                            !isDangerousBlock(block)) {
                            candidates.add(block);
                        }
                    }
                }
            }
        } else if (dimension == Level.NETHER) {
            for (Block block : BuiltInRegistries.BLOCK) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                if (id != null && id.getNamespace().equals("minecraft")) {
                    String path = id.getPath();
                    if ((path.contains("nether") || path.contains("soul") ||
                         path.contains("warped") || path.contains("crimson") ||
                         path.contains("blackstone") || path.contains("basalt") ||
                         path.contains("netherrack") || path.contains("quartz") ||
                         path.contains("magma") || path.contains("glowstone") ||
                         path.contains("ancient_debris") || path.contains("netherite")) &&
                        !isDangerousBlock(block)) {
                        candidates.add(block);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            candidates.add(Blocks.STONE);
        }

        return candidates.get(RANDOM.nextInt(candidates.size()));
    }

    private boolean isDangerousBlock(Block block) {
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR ||
               block == Blocks.BEDROCK || block == Blocks.COMMAND_BLOCK ||
               block == Blocks.CHAIN_COMMAND_BLOCK || block == Blocks.REPEATING_COMMAND_BLOCK ||
               block == Blocks.STRUCTURE_BLOCK || block == Blocks.STRUCTURE_VOID ||
               block == Blocks.BARRIER || block == Blocks.JIGSAW ||
               block == Blocks.LAVA || block == Blocks.LAVA_CAULDRON;
    }

    private void spawnRandomMobForDimension(ServerLevel world, BlockPos pos) {
        ResourceKey<Level> dimension = world.dimension();
        List<EntityType<?>> candidates = new ArrayList<>();

        if (dimension == Level.OVERWORLD) {
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                if (type.getCategory() == MobCategory.CREATURE ||
                    type.getCategory() == MobCategory.AMBIENT ||
                    type.getCategory() == MobCategory.WATER_CREATURE) {
                    if (type != EntityType.WARDEN && type != EntityType.ENDER_DRAGON &&
                        type != EntityType.WITHER && type != EntityType.ILLUSIONER) {
                        candidates.add(type);
                    }
                }
            }
        } else if (dimension == Level.NETHER) {
            candidates.add(EntityType.BLAZE);
            candidates.add(EntityType.GHAST);
            candidates.add(EntityType.MAGMA_CUBE);
            candidates.add(EntityType.PIGLIN);
            candidates.add(EntityType.PIGLIN_BRUTE);
            candidates.add(EntityType.HOGLIN);
            candidates.add(EntityType.STRIDER);
            candidates.add(EntityType.ZOGLIN);
            candidates.add(EntityType.ZOMBIFIED_PIGLIN);
            candidates.add(EntityType.ENDERMAN);
            candidates.add(EntityType.WITHER_SKELETON);
        }

        if (!candidates.isEmpty()) {
            EntityType<?> chosenType = candidates.get(RANDOM.nextInt(candidates.size()));
            Mob mob = (Mob) chosenType.create(world);
            if (mob != null) {
                mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, RANDOM.nextFloat() * 360, 0);
                world.addFreshEntity(mob);
            }
        }
    }

    private void giveStartingItems(ServerPlayer player) {
        for (ItemStack item : STARTING_ITEMS) {
            if (!player.getInventory().add(item.copy())) {
                player.drop(item.copy(), false);
            }
        }
        player.sendSystemMessage(Component.literal("§a[多人随机单方块] 已获得初始物资！"));
    }
}
