package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TestFiles {

  private static final Set<PosixFilePermission> ALL = EnumSet.allOf(PosixFilePermission.class);

  private static final Set<PosixFilePermission> READ =
      EnumSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ);

  private TestFiles() {}

  static Runnable denyListing(Path directory) throws IOException {
    return deny(
        directory,
        READ,
        Map.of(directory, AclEntryPermission.READ_DATA),
        () -> {
          try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            entries.iterator().hasNext();
          }
        });
  }

  static Runnable denyReading(Path file) throws IOException {
    return deny(
        file, ALL, Map.of(file, AclEntryPermission.READ_DATA), () -> Files.readAllBytes(file));
  }

  static Runnable denyAttributes(Path file) throws IOException {
    Map<Path, AclEntryPermission> permissions = new LinkedHashMap<>();
    permissions.put(file, AclEntryPermission.READ_ATTRIBUTES);
    permissions.put(file.getParent(), AclEntryPermission.READ_DATA);
    return deny(
        file.getParent(),
        ALL,
        permissions,
        () -> Files.readAttributes(file, BasicFileAttributes.class));
  }

  static void link(Path link, Path target) throws Exception {
    if (System.getProperty("os.name").startsWith("Windows")) {
      Process process =
          new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor()).as(output).isZero();
    } else {
      Files.createSymbolicLink(link, target);
    }
  }

  private static Runnable deny(
      Path posixPath,
      Set<PosixFilePermission> posixPermissions,
      Map<Path, AclEntryPermission> aclPermissions,
      IoAction access)
      throws IOException {
    List<Runnable> restores = new ArrayList<>();
    PosixFileAttributeView posix =
        Files.getFileAttributeView(posixPath, PosixFileAttributeView.class);
    if (posix != null) {
      Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
      Set<PosixFilePermission> kept = new HashSet<>(permissions);
      kept.removeAll(posixPermissions);
      posix.setPermissions(kept);
      restores.add(() -> uncheck(() -> posix.setPermissions(permissions)));
    } else {
      for (Map.Entry<Path, AclEntryPermission> permission : aclPermissions.entrySet()) {
        AclFileAttributeView acl =
            Files.getFileAttributeView(permission.getKey(), AclFileAttributeView.class);
        if (acl == null) {
          restores.forEach(Runnable::run);
          return null;
        }
        List<AclEntry> entries = acl.getAcl();
        List<AclEntry> denying = new ArrayList<>(entries);
        denying.add(
            0,
            AclEntry.newBuilder()
                .setType(AclEntryType.DENY)
                .setPrincipal(acl.getOwner())
                .setPermissions(permission.getValue())
                .build());
        acl.setAcl(denying);
        restores.add(0, () -> uncheck(() -> acl.setAcl(entries)));
      }
    }
    Runnable restore = () -> restores.forEach(Runnable::run);
    try {
      access.run();
      restore.run();
      return null;
    } catch (AccessDeniedException e) {
      return restore;
    }
  }

  private interface IoAction {
    void run() throws IOException;
  }

  private static void uncheck(IoAction action) {
    try {
      action.run();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
