package io.github.yukio0.greenmailtestviewer.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

/**
 * Adds the {@code greenmailTestViewer} task, which starts GreenMail Test Viewer on the records
 * under the project directory, and the {@code greenmailTestViewer} block for its settings. The
 * viewer is fetched from the repositories of the build, at the same version as this plugin, through
 * the {@code greenmailTestViewer} configuration.
 */
public class GreenMailTestViewerPlugin implements Plugin<Project> {

  /** The name of the task, the configuration and the extension. */
  public static final String NAME = "greenmailTestViewer";

  static final String VIEWER = "io.github.yukio0:greenmail-test-viewer";

  /** Creates the plugin. Gradle calls this; apply the plugin by its ID. */
  public GreenMailTestViewerPlugin() {}

  @Override
  public void apply(Project project) {
    GreenMailTestViewerExtension extension =
        project.getExtensions().create(NAME, GreenMailTestViewerExtension.class);
    extension.getViewerVersion().convention(pluginVersion());
    extension.getOpen().convention(true);

    Configuration viewer =
        project
            .getConfigurations()
            .create(
                NAME,
                configuration -> {
                  configuration.setDescription("GreenMail Test Viewer and its dependencies.");
                  configuration.setCanBeConsumed(false);
                  configuration.defaultDependencies(dependencies ->
                      dependencies.add(
                          project
                              .getDependencies()
                              .create(VIEWER + ":" + extension.getViewerVersion().get())));
                });

    project
        .getTasks()
        .register(
            NAME,
            GreenMailTestViewerTask.class,
            task -> {
              task.setGroup("verification");
              task.setDescription(
                  "Starts GreenMail Test Viewer on the emails that the tests sent. Runs until it is"
                      + " stopped with Ctrl+C.");
              task.setClasspath(viewer);
              task.getOpen().convention(extension.getOpen());
              task.getHost().convention(extension.getHost());
              task.getPort().convention(extension.getPort());
              task.getAllowedHosts().convention(extension.getAllowedHosts());
              task.getDirectories().convention(extension.getDirectories());
            });
  }

  static String pluginVersion() {
    Properties properties = new Properties();
    try (InputStream in =
        GreenMailTestViewerPlugin.class.getResourceAsStream("plugin.properties")) {
      if (in == null) {
        throw new IllegalStateException("plugin.properties is missing from the plugin");
      }
      properties.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return properties.getProperty("version");
  }
}
