package dev.epoch;

import dev.epoch.bridge.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.block.*;
import net.minecraft.nbt.*;
import net.minecraft.util.ResourceLocation;
import net.minecraft.state.Property;

/** Native registry boundary; shared rules never depend on Minecraft classes. */
final class NativeStates {
    static Map<String, String> mods() {
        Map<String, String> versions = new TreeMap<>();
        for (net.minecraftforge.forgespi.language.IModInfo mod : net.minecraftforge.fml.ModList.get().getMods())
            versions.put(mod.getModId(), mod.getVersion().toString());
        return versions;
    }

    static Context context() throws Exception {
        return new Context(CompatibilityRules.load(CompatibilityRules.directory()), mods());
    }

    static final class Context implements CompatibilityRules.SchemaLookup {
        final CompatibilityRules rules;
        final Map<String, String> mods;
        final Map<String, Map<String, Set<String>>> schemas = new HashMap<>();
        Context(CompatibilityRules rules, Map<String, String> mods) {
            this.rules = rules; this.mods = Collections.unmodifiableMap(new TreeMap<>(mods));
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        public Map<String, Set<String>> schema(String name) {
            if (schemas.containsKey(name)) return schemas.get(name);
            ResourceLocation id = new ResourceLocation(name);
            if (!net.minecraft.util.registry.Registry.BLOCK.containsKey(id)) { schemas.put(name, null); return null; }
            Map<String, Set<String>> schema = new TreeMap<>();
            for (Property prop : net.minecraft.util.registry.Registry.BLOCK.get(id).getStateDefinition().getProperties()) {
                Set<String> values = new HashSet<>();
                for (Object value : prop.getPossibleValues()) values.add(prop.getName((Comparable) value));
                schema.put(prop.getName(), values);
            }
            schemas.put(name, schema);
            return schema;
        }
        BlockStateCompatibility.Result resolve(String text) throws Exception {
            CompoundNBT tag = JsonToNBT.parseTag(text);
            Map<String, String> props = new TreeMap<>();
            CompoundNBT properties = tag.getCompound("Properties");
            for (String key : properties.getAllKeys()) props.put(key, properties.getString(key));
            return rules.resolve(new CanonicalBlockState(tag.getString("Name"), props,
                tag.contains("EpochLegacyVersion"), text), Epoch.VERSION, mods, this);
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        BlockState materialize(BlockStateCompatibility.Result result) {
            if (!result.compatible()) return null;
            BlockState state = net.minecraft.util.registry.Registry.BLOCK.get(new ResourceLocation(result.block)).defaultBlockState();
            for (Map.Entry<String, String> entry : result.properties.entrySet()) {
                Property prop = state.getBlock().getStateDefinition().getProperty(entry.getKey());
                state = state.setValue(prop, (Comparable) prop.getValue(entry.getValue()).get());
            }
            return state;
        }
        BlockState decode(String text, BlockStateCompatibility.Report report, int index) throws Exception {
            BlockStateCompatibility.Result result = resolve(text);
            report.record(index, JsonToNBT.parseTag(text).getString("Name"), result);
            return materialize(result);
        }
        Map<String, Object> audit(RegistryCatalog source) throws Exception {
            return RegistryCatalog.audit(source, Epoch.VERSION, mods, rules.fingerprint, text -> {
                BlockStateCompatibility.Result result = resolve(text);
                materialize(result); // Exercise the same native construction as a real import.
                return RegistryCatalog.Outcome.modern(result);
            });
        }
    }

    static RegistryCatalog catalog(Map<String, String> mods) {
        RegistryCatalog catalog = new RegistryCatalog(Epoch.VERSION, mods);
        for (Block block : net.minecraft.util.registry.Registry.BLOCK) {
            String id = net.minecraft.util.registry.Registry.BLOCK.getKey(block).toString();
            for (BlockState state : block.getStateDefinition().getPossibleStates())
                catalog.states.add(new RegistryCatalog.State(id, NBTUtil.writeBlockState(state).toString(), state.hasTileEntity()));
        }
        catalog.states.sort(Comparator.comparing((RegistryCatalog.State s) -> s.block).thenComparing(s -> s.snbt));
        return catalog;
    }
}
