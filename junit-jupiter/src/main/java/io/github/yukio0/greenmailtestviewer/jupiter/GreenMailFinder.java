package io.github.yukio0.greenmailtestviewer.jupiter;

import com.icegreen.greenmail.base.GreenMailOperations;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstances;

/**
 * Finds GreenMail instances in the fields of a test class, its superclasses, its enclosing classes
 * and the outer test classes that JUnit runs it in: fields whose declared type implements {@link
 * GreenMailOperations}.
 */
final class GreenMailFinder {

  private GreenMailFinder() {}

  static List<FoundGreenMail> findInFields(ExtensionContext context) {
    Finder finder = new Finder();
    for (Class<?> type = context.getRequiredTestClass();
        type != null;
        type = type.getEnclosingClass()) {
      finder.addFields(type, null);
    }
    // A @Nested class inherited from a superclass is enclosed by that superclass, but JUnit runs it
    // in a subclass, whose static fields only the parent contexts know.
    for (Optional<ExtensionContext> current = Optional.of(context);
        current.isPresent();
        current = current.get().getParent()) {
      current.get().getTestClass().ifPresent(type -> finder.addFields(type, null));
    }
    Optional<TestInstances> testInstances = context.getTestInstances();
    if (testInstances.isPresent()) {
      for (Object instance : testInstances.get().getAllInstances()) {
        finder.addFields(instance.getClass(), instance);
      }
    }
    return finder.found;
  }

  static final class FoundGreenMail {
    private final GreenMailOperations greenMail;
    private final String location;

    FoundGreenMail(GreenMailOperations greenMail, String location) {
      this.greenMail = greenMail;
      this.location = location;
    }

    GreenMailOperations greenMail() {
      return greenMail;
    }

    String location() {
      return location;
    }
  }

  private static final class Finder {
    private final List<FoundGreenMail> found = new ArrayList<>();
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    void addFields(Class<?> type, Object instance) {
      for (Class<?> current = type;
          current != null && current != Object.class;
          current = current.getSuperclass()) {
        for (Field field : current.getDeclaredFields()) {
          boolean isStatic = Modifier.isStatic(field.getModifiers());
          if (isStatic != (instance == null)
              || !GreenMailOperations.class.isAssignableFrom(field.getType())) {
            continue;
          }
          Object value = read(field, instance);
          if (value != null && seen.add(value)) {
            found.add(
                new FoundGreenMail(
                    (GreenMailOperations) value, current.getName() + "." + field.getName()));
          }
        }
      }
    }

    private static Object read(Field field, Object instance) {
      try {
        if (!field.trySetAccessible()) {
          return null;
        }
        return field.get(instance);
      } catch (IllegalAccessException | RuntimeException e) {
        return null;
      }
    }
  }
}
