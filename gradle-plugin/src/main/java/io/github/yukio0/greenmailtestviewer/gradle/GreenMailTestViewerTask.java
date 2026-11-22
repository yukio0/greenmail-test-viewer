package io.github.yukio0.greenmailtestviewer.gradle;

import java.util.ArrayList;
import java.util.List;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;

/**
 * Starts GreenMail Test Viewer, and runs until it is stopped with Ctrl+C. Arguments given with
 * {@code --args} come after the options of this task, so that they take precedence. Another task of
 * this type needs a classpath, such as {@code classpath = configurations.greenmailTestViewer}.
 */
@DisableCachingByDefault(
    because = "Runs the viewer until it is stopped and produces no output files")
public abstract class GreenMailTestViewerTask extends JavaExec {

  static final String MAIN_CLASS = "io.github.yukio0.greenmailtestviewer.viewer.ViewerMain";

  /** Sets the main class of the viewer, and turns on {@code open} by default. */
  public GreenMailTestViewerTask() {
    getMainClass().set(MAIN_CLASS);
    getOpen().convention(true);
  }

  /**
   * Whether to open the viewer in the default browser once it runs.
   *
   * @return whether to open the browser
   */
  @Input
  public abstract Property<Boolean> getOpen();

  /**
   * Does not open the browser. Gradle 8.2 and later add {@code --open} as its opposite; declaring
   * it as well would make Gradle warn in every build.
   *
   * @param noOpen true for {@code --no-open}, false for {@code --open}
   */
  @Option(option = "no-open", description = "Does not open the viewer in a browser.")
  public void setNoOpenOption(boolean noOpen) {
    getOpen().set(!noOpen);
  }

  /**
   * The address to listen on, or none for the viewer's default.
   *
   * @return the address
   */
  @Input
  @Optional
  public abstract Property<String> getHost();

  /**
   * The port to listen on, or none for the viewer's default.
   *
   * @return the port
   */
  @Input
  @Optional
  public abstract Property<Integer> getPort();

  /**
   * Sets the port from the command line.
   *
   * @param port the port, as an integer
   */
  @Option(option = "port", description = "The port to listen on. 0 picks a free port.")
  public void setPortOption(String port) {
    try {
      getPort().set(Integer.valueOf(port.trim()));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("--port must be an integer: " + port, e);
    }
  }

  /**
   * Other host names to answer requests for.
   *
   * @return the host names
   */
  @Input
  public abstract ListProperty<String> getAllowedHosts();

  /**
   * Where to look for records, relative to the project directory, or none for the project
   * directory.
   *
   * @return the directories
   */
  @Input
  public abstract ListProperty<String> getDirectories();

  /**
   * Sets the directories from the command line. A setter, because Gradle 8.0 does not accept option
   * values for a {@link ListProperty}.
   *
   * @param directories the directories, relative to the project directory
   */
  @Option(
      option = "dir",
      description =
          "Where to look for records, instead of the project. Can be given more than once.")
  public void setDirectoryOption(List<String> directories) {
    getDirectories().set(directories);
  }

  @Override
  public void exec() {
    List<String> arguments = new ArrayList<>(viewerArguments());
    arguments.addAll(getArgs());
    setArgs(arguments);
    super.exec();
  }

  private List<String> viewerArguments() {
    List<String> arguments = new ArrayList<>();
    for (String directory : getDirectories().get()) {
      arguments.add("--dir");
      arguments.add(directory);
    }
    if (getHost().isPresent()) {
      arguments.add("--host");
      arguments.add(getHost().get());
    }
    if (getPort().isPresent()) {
      arguments.add("--port");
      arguments.add(String.valueOf(getPort().get()));
    }
    for (String host : getAllowedHosts().get()) {
      arguments.add("--allowed-host");
      arguments.add(host);
    }
    if (getOpen().get()) {
      arguments.add("--open");
    }
    return arguments;
  }
}
