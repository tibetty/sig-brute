package me.tibetty.sigbrute;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ModuleBoundaryTest {

    private static final Set<String> EXPORTED_PACKAGES = Set.of("me.tibetty.sigbrute.model",
        "me.tibetty.sigbrute.parser", "me.tibetty.sigbrute.search", "me.tibetty.sigbrute.decode",
        "me.tibetty.sigbrute.decode.strategy", "me.tibetty.sigbrute.decode.emit",
        "me.tibetty.sigbrute.expander", "me.tibetty.sigbrute.util");

    @Test
    void stablePackagesAreExportedInModuleDescriptor() {
        var exports = exportSources(moduleDescriptor());
        assertTrue(exports.containsAll(EXPORTED_PACKAGES));
    }

    @Test
    void inferPackageIsNotExportedInModuleDescriptor() {
        var exports = exportSources(moduleDescriptor());
        assertFalse(exports.contains("me.tibetty.sigbrute.decode.infer"));
    }

    @Test
    void cliEntryPackageIsNotExportedInModuleDescriptor() {
        var exports = exportSources(moduleDescriptor());
        assertFalse(exports.contains("me.tibetty.sigbrute"));
    }

    private static Set<String> exportSources(ModuleDescriptor descriptor) {
        return descriptor.exports().stream().map(ModuleDescriptor.Exports::source)
            .collect(Collectors.toSet());
    }

    private static ModuleDescriptor moduleDescriptor() {
        var root = Path.of("build/classes/java/main").toAbsolutePath();
        return ModuleFinder.of(root).findAll().stream().map(ref -> ref.descriptor())
            .filter(d -> d.name().equals("me.tibetty.sigbrute")).findFirst()
            .orElseThrow(() -> new AssertionError(
                "module me.tibetty.sigbrute not found under " + root));
    }
}
