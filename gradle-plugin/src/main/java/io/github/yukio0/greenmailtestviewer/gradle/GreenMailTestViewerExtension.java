package io.github.yukio0.greenmailtestviewer.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The settings of the {@code greenmailTestViewer} task, in the {@code greenmailTestViewer} block:
 *
 * <pre>
 * greenmailTestViewer {
 *   port = 9000
 *   open = false
 * }
 * </pre>
 *
 * <p>Command-line options of the task, such as {@code --port=9000}, take precedence.
 */
public abstract class GreenMailTestViewerExtension {

  /** Creates the settings. The plugin calls this, and Gradle implements the properties. */
  public GreenMailTestViewerExtension() {}

  /**
   * The version of the viewer, fetched from the repositories of the build. By default, the version
   * of this plugin.
   *
   * @return the version
   */
  public abstract Property<String> getViewerVersion();

  /**
   * Whether to open the viewer in the default browser. By default, true.
   *
   * @return whether to open the browser
   */
  public abstract Property<Boolean> getOpen();

  /**
   * The address to listen on. By default, {@code 127.0.0.1}.
   *
   * @return the address
   */
  public abstract Property<String> getHost();

  /**
   * The port to listen on. By default, 3080.
   *
   * @return the port
   */
  public abstract Property<Integer> getPort();

  /**
   * Other host names to answer requests for, such as the name of this machine.
   *
   * @return the host names
   */
  public abstract ListProperty<String> getAllowedHosts();

  /**
   * Where to look for records, relative to the project directory. By default, the project
   * directory.
   *
   * @return the directories
   */
  public abstract ListProperty<String> getDirectories();
}
