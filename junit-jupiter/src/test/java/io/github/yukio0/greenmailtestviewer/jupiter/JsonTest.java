package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void writesObjectsWithIndentationInInsertionOrder() {
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("formatVersion", 1);
    object.put("name", "value");
    object.put("missing", null);
    object.put("list", Arrays.asList("a", "b"));

    assertThat(Json.write(object))
        .isEqualTo(
            "{\n"
                + "  \"formatVersion\": 1,\n"
                + "  \"name\": \"value\",\n"
                + "  \"missing\": null,\n"
                + "  \"list\": [\"a\", \"b\"]\n"
                + "}\n");
  }

  @Test
  void escapesStringsSoThatJsonParsersReadThemBack() throws Exception {
    String tricky = "quote \" backslash \\ newline \n tab \t control \u0001 line separator   日本語";
    Map<String, Object> object = new LinkedHashMap<>();
    object.put("value", tricky);

    JsonNode parsed = new ObjectMapper().readTree(Json.write(object));

    assertThat(parsed.path("value").asText()).isEqualTo(tricky);
    assertThat(Json.write(object)).contains("\\u0001").contains("\\u2028").contains("日本語");
  }

  @Test
  void writesEmptyObjects() {
    assertThat(Json.write(new LinkedHashMap<>())).isEqualTo("{}\n");
  }
}
