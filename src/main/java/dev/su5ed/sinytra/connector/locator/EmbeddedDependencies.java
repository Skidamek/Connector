package dev.su5ed.sinytra.connector.locator;

import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.rethrowFunction;
import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;

/**
 * Handles locating and retrieving embedded jars that are shipped with Connector,
 * based on jar manifest attributes.
 */
public final class EmbeddedDependencies {
    // Manifest attribute name prefix for embedded dependencies
    private static final String JIJ_ATTRIBUTE_PREFIX = "Embedded-Dependencies-";
    // Embedded mod jar name
    private static final String MOD_JIJ_DEP = "Mod";

    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
    // Path to the jar this class is loaded from
    private static final Path SELF_PATH = uncheck(() -> {
        URL jarLocation = ConnectorLocator.class.getProtectionDomain().getCodeSource().getLocation();
        return Path.of(jarLocation.toURI());
    });
    private static final Attributes ATTRIBUTES = readManifestAttributes();

    /**
     * {@return a stream of paths of all embedded jars}
     */
    public static Stream<Path> locateEmbeddedJars() {
        return Stream.of(MOD_JIJ_DEP).map(rethrowFunction(EmbeddedDependencies::getJarInJar));
    }

    /**
     * Get the root path inside an embedded jar.
     *
     * @param name the identifier of the jar, will be used together with the
     *             {@link #JIJ_ATTRIBUTE_PREFIX} to get the path to it from manifest attributes
     * @return the root path of the jar's file system
     */
    private static Path getJarInJar(String name) throws IOException, URISyntaxException {
        String depName = ATTRIBUTES.getValue(JIJ_ATTRIBUTE_PREFIX + name);
        if (depName == null) {
            throw new IllegalArgumentException("Required " + name + " embedded jar not found");
        }
        // Code taken from JarInJarDependencyLocator#loadModFileFrom
        Path pathInModFile = SELF_PATH.resolve(depName);
        URI filePathUri = new URI("jij:" + pathInModFile.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
        Map<String, ?> outerFsArgs = ImmutableMap.of("packagePath", pathInModFile);
        FileSystem zipFS = FileSystems.newFileSystem(filePathUri, outerFsArgs);
        return zipFS.getPath("/");
    }

    private static Attributes readManifestAttributes() {
        Path manifestPath = SELF_PATH.resolve(MANIFEST_PATH);
        Manifest manifest;
        try (InputStream is = Files.newInputStream(manifestPath)) {
            manifest = new Manifest(is);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return manifest.getMainAttributes();
    }

    private EmbeddedDependencies() {}
}
