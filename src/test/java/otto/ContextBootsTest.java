package otto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import otto.harness.WireSeamTest;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBootsTest extends WireSeamTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theRealSpringContextBoots() {
        assertThat(context).isNotNull();
    }
}
