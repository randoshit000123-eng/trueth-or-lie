package de.truthorlie;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Sucht für jeden Spieler einen zufälligen Ort bzw. ein Ereignis ("Szenario").
 * Die Suche läuft vor dem Teleport (während des Countdowns), damit beim Start niemand laggt.
 */
public final class Scenarios {
	private Scenarios() {
	}

	public enum Type {
		STRUCTURE("structure", "Struktur", 4),
		MOB("mob", "Mob-Begegnung", 4),
		NETHER("nether", "Nether", 2),
		CAVE("cave", "Höhle", 2),
		OCEAN("ocean", "Ozean", 2),
		WILD("wild", "Wildnis", 2);

		public final String id;
		public final String display;
		public final int weight;

		Type(String id, String display, int weight) {
			this.id = id;
			this.display = display;
			this.weight = weight;
		}

		public static Type byId(String id) {
			for (Type t : values()) {
				if (t.id.equals(id)) {
					return t;
				}
			}
			return null;
		}
	}

	/** Ergebnis der Planung: wohin, mit welchem Titel, und welche Commands nach dem Teleport laufen. */
	public record Placement(Type type, String dim, double x, double y, double z, float yaw, String title, List<String> commands) {
		public String coords() {
			return (int) x + " " + (int) y + " " + (int) z;
		}
	}

	// ------------------------------------------------------------------ Dimensionen

	public static ResourceKey<Level> dimKey(String dim) {
		return switch (dim) {
			case "the_nether" -> Level.NETHER;
			case "the_end" -> Level.END;
			default -> Level.OVERWORLD;
		};
	}

	public static String dimOf(ServerLevel level) {
		if (level.dimension() == Level.NETHER) {
			return "the_nether";
		}
		if (level.dimension() == Level.END) {
			return "the_end";
		}
		return "overworld";
	}

	private static String exec(String dim) {
		return "execute in minecraft:" + dim + " run ";
	}

	// ------------------------------------------------------------------ Planung

	public static Type pickType(TolConfig cfg, Type avoid) {
		List<Type> pool = new ArrayList<>();
		for (Type t : Type.values()) {
			if (cfg.disabledScenarios.contains(t.id)) {
				continue;
			}
			for (int i = 0; i < t.weight; i++) {
				pool.add(t);
			}
		}
		if (pool.isEmpty()) {
			return Type.WILD;
		}
		// Nicht zweimal hintereinander dasselbe Szenario, wenn es Alternativen gibt.
		List<Type> filtered = new ArrayList<>(pool);
		filtered.removeIf(t -> t == avoid);
		if (!filtered.isEmpty()) {
			pool = filtered;
		}
		return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
	}

	public static Placement plan(MinecraftServer server, TolConfig cfg, Type type) {
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		double cx = cfg.lobbyX;
		double cz = cfg.lobbyZ;
		if ("the_nether".equals(cfg.lobbyDim)) {
			cx *= 8;
			cz *= 8;
		} else if ("the_end".equals(cfg.lobbyDim)) {
			cx = 0;
			cz = 0;
		}
		Placement result = null;
		try {
			result = switch (type) {
				case STRUCTURE -> planStructure(overworld, cfg, cx, cz);
				case MOB -> planMob(overworld, cfg, cx, cz);
				case NETHER -> planNether(server, cfg, cx, cz);
				case CAVE -> planCave(overworld, cfg, cx, cz);
				case OCEAN -> planOcean(overworld, cfg, cx, cz);
				case WILD -> planWild(overworld, cfg, cx, cz);
			};
		} catch (Exception e) {
			TruthOrLieMod.LOGGER.warn("Szenario {} fehlgeschlagen, nutze Wildnis", type, e);
		}
		if (result == null) {
			result = planWild(overworld, cfg, cx, cz);
		}
		return result;
	}

	// ------------------------------------------------------------------ Hilfsfunktionen

	private static BlockPos ringPoint(TolConfig cfg, double cx, double cz, double scale) {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		double min = Math.min(cfg.minDistance, cfg.maxDistance) * scale;
		double max = Math.max(cfg.minDistance, cfg.maxDistance) * scale;
		double angle = r.nextDouble() * Math.PI * 2;
		double dist = min + r.nextDouble() * Math.max(1, max - min);
		return new BlockPos((int) (cx + Math.cos(angle) * dist), 0, (int) (cz + Math.sin(angle) * dist));
	}

	private static BlockPos surface(ServerLevel level, int x, int z) {
		return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
	}

	private static boolean isWet(ServerLevel level, BlockPos top) {
		return !level.getFluidState(top.below()).isEmpty() || !level.getFluidState(top).isEmpty();
	}

	private static boolean dangerousGround(ServerLevel level, BlockPos top) {
		BlockState g = level.getBlockState(top.below());
		return g.is(Blocks.CACTUS) || g.is(Blocks.MAGMA_BLOCK) || g.is(Blocks.FIRE) || g.is(Blocks.SOUL_FIRE);
	}

	private static boolean isFree(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return s.isAir() && s.getFluidState().isEmpty();
	}

	private static boolean isGround(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		return !s.isAir() && s.getFluidState().isEmpty() && !s.is(Blocks.MAGMA_BLOCK) && !s.is(Blocks.CACTUS)
				&& !s.is(Blocks.FIRE) && !s.is(Blocks.SOUL_FIRE) && !s.is(Blocks.BEDROCK);
	}

	private static float randomYaw() {
		return ThreadLocalRandom.current().nextFloat() * 360f - 180f;
	}

	private static String fmt(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	// ------------------------------------------------------------------ Wildnis

	private static Placement planWild(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		BlockPos last = null;
		for (int i = 0; i < 25; i++) {
			BlockPos p = ringPoint(cfg, cx, cz, 1);
			BlockPos top = surface(overworld, p.getX(), p.getZ());
			last = top;
			if (!isWet(overworld, top) && !dangerousGround(overworld, top)) {
				break;
			}
		}
		return new Placement(Type.WILD, "overworld", last.getX() + 0.5, last.getY(), last.getZ() + 0.5, randomYaw(),
				"Mitten in der Wildnis", new ArrayList<>());
	}

	// ------------------------------------------------------------------ Struktur

	private record Struct(String tag, String name) {
	}

	private static final List<Struct> STRUCTS = List.of(
			new Struct("village", "Dorf"),
			new Struct("on_woodland_explorer_maps", "Waldanwesen"),
			new Struct("on_ocean_explorer_maps", "Ozeanmonument"),
			new Struct("on_trial_chambers_maps", "Prüfungskammer"),
			new Struct("on_desert_explorer_maps", "Wüstentempel"),
			new Struct("on_jungle_explorer_maps", "Dschungeltempel"),
			new Struct("on_swamp_explorer_maps", "Hexenhütte"),
			new Struct("ruined_portal", "Zerstörtes Portal"),
			new Struct("shipwreck", "Schiffswrack"),
			new Struct("ocean_ruin", "Ozean-Ruine"),
			new Struct("mineshaft", "Verlassene Mine"));

	private static Placement planStructure(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		List<Struct> order = new ArrayList<>(STRUCTS);
		Collections.shuffle(order);
		for (int i = 0; i < Math.min(4, order.size()); i++) {
			Struct s = order.get(i);
			BlockPos origin = ringPoint(cfg, cx, cz, 1);
			TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE, Identifier.fromNamespaceAndPath("minecraft", s.tag()));
			BlockPos found = overworld.findNearestMapStructure(tag, origin, 100, false);
			if (found == null) {
				continue;
			}
			BlockPos top = surface(overworld, found.getX(), found.getZ());
			List<String> cmds = new ArrayList<>();
			if (isWet(overworld, top)) {
				cmds.add(exec("overworld") + "summon minecraft:oak_boat " + fmt(top.getX() + 0.5) + " " + top.getY() + " " + fmt(top.getZ() + 0.5) + " {Tags:[\"tol_mob\"]}");
			}
			return new Placement(Type.STRUCTURE, "overworld", top.getX() + 0.5, top.getY(), top.getZ() + 0.5, randomYaw(),
					"Struktur: " + s.name(), cmds);
		}
		return null;
	}

	// ------------------------------------------------------------------ Mob-Begegnung

	private record MobDef(String name, String id, int count, int minDifficulty, int weight) {
	}

	private static final List<MobDef> MOBS = List.of(
			new MobDef("Eine Kuh", "cow", 1, 0, 2),
			new MobDef("Ein Rudel Pandas", "panda", 3, 0, 2),
			new MobDef("Ein Dorfbewohner", "villager", 1, 0, 2),
			new MobDef("Ein Papagei", "parrot", 1, 0, 1),
			new MobDef("Ein Creeper", "creeper", 1, 1, 4),
			new MobDef("Zombie-Horde", "zombie", 5, 1, 4),
			new MobDef("Skelett-Trupp", "skeleton", 3, 1, 3),
			new MobDef("Eine Spinne", "spider", 1, 1, 2),
			new MobDef("Ein Enderman", "enderman", 1, 1, 3),
			new MobDef("Eine Hexe", "witch", 1, 1, 2),
			new MobDef("Ein Wolfsrudel", "wolf", 4, 1, 2),
			new MobDef("Ein Eisengolem", "iron_golem", 1, 1, 2),
			new MobDef("Plünderer-Trupp", "pillager", 4, 1, 2),
			new MobDef("Ein Verwüster", "ravager", 1, 2, 3),
			new MobDef("Ein Evoker", "evoker", 1, 2, 2),
			new MobDef("Ein Warden", "warden", 1, 2, 1));

	private static Placement planMob(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		List<MobDef> pool = new ArrayList<>();
		for (MobDef m : MOBS) {
			if (m.minDifficulty() <= cfg.mobDifficulty) {
				for (int i = 0; i < m.weight(); i++) {
					pool.add(m);
				}
			}
		}
		if (pool.isEmpty()) {
			return null;
		}
		ThreadLocalRandom r = ThreadLocalRandom.current();
		MobDef mob = pool.get(r.nextInt(pool.size()));
		Placement base = planWild(overworld, cfg, cx, cz);
		float yaw = randomYaw();
		// Blickrichtung: yaw 0 = +Z, yaw 90 = -X
		double fx = -Math.sin(Math.toRadians(yaw));
		double fz = Math.cos(Math.toRadians(yaw));
		List<String> cmds = new ArrayList<>();
		for (int i = 0; i < mob.count(); i++) {
			double mx = base.x() + fx * 4 + (mob.count() > 1 ? r.nextDouble(-2, 2) : 0);
			double mz = base.z() + fz * 4 + (mob.count() > 1 ? r.nextDouble(-2, 2) : 0);
			BlockPos ground = surface(overworld, (int) Math.floor(mx), (int) Math.floor(mz));
			cmds.add(exec("overworld") + "summon minecraft:" + mob.id() + " " + fmt(mx) + " " + ground.getY() + " " + fmt(mz) + " {Tags:[\"tol_mob\"]}");
		}
		return new Placement(Type.MOB, "overworld", base.x(), base.y(), base.z(), yaw, "Mob-Begegnung: " + mob.name(), cmds);
	}

	// ------------------------------------------------------------------ Nether

	private static Placement planNether(MinecraftServer server, TolConfig cfg, double cx, double cz) {
		ServerLevel nether = server.getLevel(Level.NETHER);
		if (nether == null) {
			return null;
		}
		for (int attempt = 0; attempt < 12; attempt++) {
			BlockPos p = ringPoint(cfg, cx / 8.0, cz / 8.0, 1.0 / 8.0);
			for (int y = 100; y > 34; y--) {
				BlockPos pos = new BlockPos(p.getX(), y, p.getZ());
				if (isFree(nether, pos) && isFree(nether, pos.above()) && isGround(nether, pos.below())) {
					return new Placement(Type.NETHER, "the_nether", pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, randomYaw(),
							"Im Nether", new ArrayList<>());
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ Höhle

	private static Placement planCave(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		for (int attempt = 0; attempt < 4; attempt++) {
			BlockPos base = ringPoint(cfg, cx, cz, 1);
			for (int dx = -6; dx <= 6; dx += 2) {
				for (int dz = -6; dz <= 6; dz += 2) {
					int x = base.getX() + dx;
					int z = base.getZ() + dz;
					int surfaceY = surface(overworld, x, z).getY();
					for (int y = Math.min(surfaceY - 10, 45); y > -55; y--) {
						BlockPos pos = new BlockPos(x, y, z);
						if (isFree(overworld, pos) && isFree(overworld, pos.above()) && isGround(overworld, pos.below())) {
							return new Placement(Type.CAVE, "overworld", x + 0.5, y, z + 0.5, randomYaw(), "Tief in einer Höhle", new ArrayList<>());
						}
					}
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ Ozean

	private static Placement planOcean(ServerLevel overworld, TolConfig cfg, double cx, double cz) {
		for (int attempt = 0; attempt < 30; attempt++) {
			BlockPos p = ringPoint(cfg, cx, cz, 1);
			BlockPos top = surface(overworld, p.getX(), p.getZ());
			BlockPos below = top.below();
			if (!overworld.getFluidState(below).isEmpty() && !overworld.getFluidState(below.below(3)).isEmpty()) {
				List<String> cmds = new ArrayList<>();
				cmds.add(exec("overworld") + "summon minecraft:oak_boat " + fmt(top.getX() + 0.5) + " " + top.getY() + " " + fmt(top.getZ() + 0.5) + " {Tags:[\"tol_mob\"]}");
				return new Placement(Type.OCEAN, "overworld", top.getX() + 0.5, top.getY(), top.getZ() + 0.5, randomYaw(),
						"Mitten im Ozean", cmds);
			}
		}
		return null;
	}
}
