package com.conductor.creative;

import com.conductor.creative.SlideshowTextFields.Edit;
import com.conductor.creative.SlideshowTextFields.TextChange;
import com.conductor.creative.SlideshowTextFields.TextField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlideshowTextFieldsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode spec(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final String RICH = """
            {"id":"s","slides":[
              {"type":"photo","bg":"media:1","texts":[{"t":"Hook","y":20},{"t":"Second"}]},
              {"type":"screen","shot":"media:2","callouts":[{"x":1,"y":2,"label":"Tap"}],"texts":[{"t":"Look","y":10}]},
              {"type":"notes","title":"List","date":"Mon","items":["a",{"t":"b","done":true}],"paras":["p1","p2"]},
              {"type":"grid","photos":[{"src":"media:3","badge":"NEW","label":"L1"},{"src":"media:4"}]},
              {"type":"tier","rows":[{"k":"S","c":"#fff","items":["x","y"]},{"k":"A","c":"#000","items":["z"]}]},
              {"type":"week","days":[{"d":"Mon","t":"Run","img":"media:5","icon":"R"}]},
              {"type":"receipt","head":"Total","sections":[{"h":"Food","items":["i1","i2"]}],"count":"3"},
              {"type":"placard","title":"T","ex":"e.g.","lines":[{"k":"K","v":"V"},{"v":"only v"}]},
              {"type":"messages","bubbles":[{"from":"me","t":"hi"},{"from":"them","t":"yo"}]}
            ]}""";

    @Test
    void listReportsEveryEditableStringWithOneBasedSlideAndConcretePaths() {
        List<TextField> fields = SlideshowTextFields.list(spec(RICH));

        assertThat(fields).contains(
                new TextField(1, "texts[0].t", "Hook"),
                new TextField(1, "texts[1].t", "Second"),
                new TextField(2, "callouts[0].label", "Tap"),
                new TextField(2, "texts[0].t", "Look"),
                new TextField(3, "title", "List"),
                new TextField(3, "date", "Mon"),
                new TextField(3, "items[0]", "a"),
                new TextField(3, "items[1].t", "b"),
                new TextField(3, "paras[0]", "p1"),
                new TextField(3, "paras[1]", "p2"),
                new TextField(4, "photos[0].badge", "NEW"),
                new TextField(4, "photos[0].label", "L1"),
                new TextField(5, "rows[0].k", "S"),
                new TextField(5, "rows[0].items[1]", "y"),
                new TextField(5, "rows[1].items[0]", "z"),
                new TextField(6, "days[0].d", "Mon"),
                new TextField(6, "days[0].t", "Run"),
                new TextField(6, "days[0].icon", "R"),
                new TextField(7, "head", "Total"),
                new TextField(7, "sections[0].h", "Food"),
                new TextField(7, "sections[0].items[1]", "i2"),
                new TextField(7, "count", "3"),
                new TextField(8, "title", "T"),
                new TextField(8, "ex", "e.g."),
                new TextField(8, "lines[0].k", "K"),
                new TextField(8, "lines[0].v", "V"),
                new TextField(8, "lines[1].v", "only v"),
                new TextField(9, "bubbles[0].t", "hi"),
                new TextField(9, "bubbles[1].t", "yo"));
    }

    @Test
    void listLeavesOutNonEditableAndNonStringValues() {
        List<String> paths = SlideshowTextFields.list(RICH_SPEC()).stream().map(f -> f.slide() + ":" + f.path()).toList();

        // bg / shot / src / img / from / c are not text; the object form of items has no string at items[1]
        assertThat(paths).noneMatch(p -> p.endsWith(".bg") || p.endsWith(":bg") || p.contains("shot")
                || p.contains(".src") || p.contains(".img") || p.contains(".from") || p.endsWith(".c"));
        assertThat(paths).doesNotContain("3:items[1]");
        assertThat(paths).doesNotContain("4:photos[1].badge");
    }

    private static JsonNode RICH_SPEC() {
        return spec(RICH);
    }

    @Test
    void listIsEmptyForASpecWithoutSlides() {
        assertThat(SlideshowTextFields.list(spec("{\"id\":\"x\"}"))).isEmpty();
        assertThat(SlideshowTextFields.list(null)).isEmpty();
        assertThat(SlideshowTextFields.list(spec("{\"slides\":[]}"))).isEmpty();
        assertThat(SlideshowTextFields.list(spec("{\"slides\":[{\"texts\":\"nope\"}]}"))).isEmpty();
    }

    @Test
    void itemsAreListedInBothTheStringAndTheObjectForm() {
        List<TextField> fields = SlideshowTextFields.list(spec("{\"slides\":[{\"items\":[\"plain\",{\"t\":\"obj\"},5]}]}"));
        assertThat(fields).containsExactly(new TextField(1, "items[0]", "plain"), new TextField(1, "items[1].t", "obj"));
    }

    @Test
    void isEditablePathAcceptsConcretePathsOfRegistryPatternsOnly() {
        assertThat(SlideshowTextFields.isEditablePath("texts[3].t")).isTrue();
        assertThat(SlideshowTextFields.isEditablePath("rows[1].items[2]")).isTrue();
        assertThat(SlideshowTextFields.isEditablePath("title")).isTrue();
        assertThat(SlideshowTextFields.isEditablePath("texts[].t")).isFalse();
        assertThat(SlideshowTextFields.isEditablePath("bg")).isFalse();
        assertThat(SlideshowTextFields.isEditablePath("texts[0].y")).isFalse();
        assertThat(SlideshowTextFields.isEditablePath("texts[0]")).isFalse();
        assertThat(SlideshowTextFields.isEditablePath("texts[-1].t")).isFalse();
        assertThat(SlideshowTextFields.isEditablePath("texts[0].t.x")).isFalse();
        assertThat(SlideshowTextFields.isEditablePath("../title")).isFalse();
        assertThat(SlideshowTextFields.isEditablePath("")).isFalse();
        assertThat(SlideshowTextFields.isEditablePath(null)).isFalse();
    }

    @Test
    void applyReplacesStringsAtIndicesAndNestedPathsWithoutMutatingTheInput() {
        JsonNode original = spec(RICH);
        JsonNode before = original.deepCopy();

        JsonNode edited = SlideshowTextFields.apply(original, List.of(
                new Edit(1, "texts[1].t", "Changed"),
                new Edit(3, "items[0]", "A"),
                new Edit(3, "items[1].t", "B"),
                new Edit(5, "rows[0].items[1]", "Y"),
                new Edit(7, "sections[0].items[0]", "I1")));

        assertThat(original).isEqualTo(before);
        assertThat(edited.at("/slides/0/texts/1/t").asText()).isEqualTo("Changed");
        assertThat(edited.at("/slides/0/texts/0/t").asText()).isEqualTo("Hook");
        assertThat(edited.at("/slides/2/items/0").asText()).isEqualTo("A");
        assertThat(edited.at("/slides/2/items/1/t").asText()).isEqualTo("B");
        assertThat(edited.at("/slides/2/items/1/done").asBoolean()).isTrue();
        assertThat(edited.at("/slides/4/rows/0/items/1").asText()).isEqualTo("Y");
        assertThat(edited.at("/slides/6/sections/0/items/0").asText()).isEqualTo("I1");
    }

    @Test
    void applyWithNoEditsReturnsAnEqualCopy() {
        JsonNode original = spec(RICH);
        JsonNode copy = SlideshowTextFields.apply(original, List.of());
        assertThat(copy).isEqualTo(original).isNotSameAs(original);
    }

    @Test
    void applyRejectsAPathOutsideTheRegistry() {
        assertThatThrownBy(() -> SlideshowTextFields.apply(spec(RICH), List.of(new Edit(1, "bg", "media:x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class).hasMessageContaining("not an editable");
        assertThatThrownBy(() -> SlideshowTextFields.apply(spec(RICH), List.of(new Edit(1, "texts[0].y", "5"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
        assertThatThrownBy(() -> SlideshowTextFields.apply(spec(RICH), List.of(new Edit(1, "texts[].t", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
    }

    @Test
    void applyRejectsAMissingPathWithoutCreatingIt() {
        JsonNode s = spec(RICH);
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(1, "texts[7].t", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class).hasMessageContaining("no such field");
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(1, "title", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(1, "rows[0].items[0]", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(4, "photos[1].badge", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
    }

    @Test
    void applyRejectsANonStringTarget() {
        JsonNode s = spec("{\"slides\":[{\"items\":[{\"t\":5},true],\"title\":null,\"date\":{\"x\":1}}]}");
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(1, "items[0].t", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class).hasMessageContaining("not text");
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(1, "items[1]", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(1, "title", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(1, "date", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
    }

    @Test
    void applyRejectsABadSlideNullTextAndOverlongText() {
        JsonNode s = spec(RICH);
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(0, "title", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class).hasMessageContaining("no such slide");
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(10, "title", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(3, "title", null))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(3, "title", "x".repeat(501)))))
                .isInstanceOf(SlideshowTextFields.EditException.class).hasMessageContaining("longer");
        // exactly the limit is fine
        assertThat(SlideshowTextFields.apply(s, List.of(new Edit(3, "title", "x".repeat(500)))).at("/slides/2/title").asText())
                .hasSize(500);
    }

    @Test
    void aFailingEditLeavesNothingAppliedAndTheInputUntouched() {
        JsonNode s = spec(RICH);
        JsonNode before = s.deepCopy();
        assertThatThrownBy(() -> SlideshowTextFields.apply(s, List.of(new Edit(3, "title", "ok"), new Edit(3, "bg", "x"))))
                .isInstanceOf(SlideshowTextFields.EditException.class);
        assertThat(s).isEqualTo(before);
    }

    @Test
    void diffOfAnUnchangedSpecIsEmpty() {
        assertThat(SlideshowTextFields.diff(spec(RICH), spec(RICH))).isEmpty();
        assertThat(SlideshowTextFields.diffJson(spec(RICH), spec(RICH))).isEmpty();
    }

    @Test
    void diffReportsEachChangedPathWithOldAndNew() {
        JsonNode a = spec(RICH);
        JsonNode b = SlideshowTextFields.apply(a, List.of(
                new Edit(1, "texts[0].t", "New hook"), new Edit(5, "rows[1].items[0]", "zz"), new Edit(3, "items[1].t", "B2")));

        assertThat(SlideshowTextFields.diff(a, b)).containsExactly(
                new TextChange(1, "texts[0].t", "Hook", "New hook"),
                new TextChange(3, "items[1].t", "b", "B2"),
                new TextChange(5, "rows[1].items[0]", "z", "zz"));
    }

    @Test
    void diffIgnoresChangesToNonEditableFields() {
        JsonNode a = spec("{\"slides\":[{\"bg\":\"media:1\",\"texts\":[{\"t\":\"x\",\"y\":10}]}]}");
        JsonNode b = spec("{\"slides\":[{\"bg\":\"media:2\",\"texts\":[{\"t\":\"x\",\"y\":55}]}]}");
        assertThat(SlideshowTextFields.diff(a, b)).isEmpty();
    }

    @Test
    void diffOnlyCoversSlidesBothSpecsHave() {
        JsonNode a = spec("{\"slides\":[{\"title\":\"one\"},{\"title\":\"two\"}]}");
        JsonNode b = spec("{\"slides\":[{\"title\":\"ONE\"},{\"title\":\"two\"},{\"title\":\"three\"}]}");
        assertThat(SlideshowTextFields.diff(a, b)).containsExactly(new TextChange(1, "title", "one", "ONE"));
        assertThat(SlideshowTextFields.diff(b, a)).containsExactly(new TextChange(1, "title", "ONE", "one"));
    }

    @Test
    void diffReportsAPathPresentOnOneSideOnlyWithNull() {
        JsonNode a = spec("{\"slides\":[{\"texts\":[{\"t\":\"a\"}]}]}");
        JsonNode b = spec("{\"slides\":[{\"texts\":[{\"t\":\"a\"},{\"t\":\"b\"}]}]}");
        assertThat(SlideshowTextFields.diff(a, b)).containsExactly(new TextChange(1, "texts[1].t", null, "b"));
        assertThat(SlideshowTextFields.diff(b, a)).containsExactly(new TextChange(1, "texts[1].t", "b", null));
    }

    @Test
    void diffJsonUsesTheStoredShape() {
        JsonNode a = spec("{\"slides\":[{\"title\":\"one\"}]}");
        JsonNode b = spec("{\"slides\":[{\"title\":\"two\"}]}");
        assertThat(SlideshowTextFields.diffJson(a, b)).isEqualTo(
                spec("[{\"slide\":1,\"path\":\"title\",\"old\":\"one\",\"new\":\"two\"}]"));
    }
}
