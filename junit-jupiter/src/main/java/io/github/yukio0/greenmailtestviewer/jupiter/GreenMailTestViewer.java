package io.github.yukio0.greenmailtestviewer.jupiter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Records the emails that GreenMail receives in each test of the annotated class, for the viewer.
 *
 * <p>GreenMail instances are found in the fields of the test class, so the existing setup stays as
 * it is:
 *
 * <pre>{@code
 * @GreenMailTestViewer
 * class SignupMailTest {
 *   @RegisterExtension
 *   static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);
 *
 *   @Test
 *   void sendsWelcomeMail() {
 *     // ...
 *   }
 * }
 * }</pre>
 *
 * <p>Register instances that are not in a field with {@link
 * GreenMailTestViewerExtension#watch(com.icegreen.greenmail.base.GreenMailOperations)}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(GreenMailTestViewerExtension.class)
public @interface GreenMailTestViewer {

  /**
   * How long to wait after each test method and each dynamic test to allow asynchronous emails to
   * arrive before collection.
   *
   * @return the time to wait in milliseconds, {@code 0} by default
   */
  long asyncWaitMillis() default 0;
}
