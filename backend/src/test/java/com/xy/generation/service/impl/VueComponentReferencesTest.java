package com.xy.generation.service.impl;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class VueComponentReferencesTest {
    private static final String CARD = "src/components/Card.vue";

    @ParameterizedTest
    @ValueSource(strings = {"export { default as Card } from '../components/Card.vue';",
            "const load = () => import(/* chunk */ '../components/Card.vue');",
            "import '/src/components/Card.vue?raw';"})
    void resolvesLiteralModuleReferencesFromNestedJsFiles(String code) {
        assertThat(VueComponentReferences.findError(Map.of("src/pages/cards.js", code), Set.of(CARD)))
                .contains("src/pages/cards.js", CARD);
    }

    @Test
    void checksInterpolationsWithinNestedTemplates() {
        String code = "<template><template v-if=\"show\"><p>{{ import('./components/Card.vue') }}</p></template></template>";
        assertThat(VueComponentReferences.findError(Map.of("src/App.vue", code), Set.of(CARD))).contains(CARD);
    }

    @Test
    void checksTheFinalTreeAndIgnoresCommentsTextAndDifferentComponentsOfTheSameName() {
        String code = """
                <template><p title="import('./components/Card.vue')">import Card from './components/Card.vue'</p>
                <div v-pre><span>{{ import('./components/Card.vue') }}</span></div></template>
                <script setup>
                // import './components/Card.vue'
                const example = "import Card from './components/Card.vue'"
                const pattern = /import '.\\/components\\/Card.vue'/
                /* export { default } from './components/Card.vue' */
                import Other from './other/Card.vue'
                </script>
                """;
        assertThat(VueComponentReferences.findError(Map.of("src/App.vue", code), Set.of(CARD))).isNull();
        var restored = Map.of("src/App.vue", "<script setup>import Card from './components/Card.vue'</script>",
                CARD, "<template>新卡片</template>");
        assertThat(VueComponentReferences.findError(restored, Set.of(CARD))).isNull();
    }
}
