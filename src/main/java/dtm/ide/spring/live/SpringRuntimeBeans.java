package dtm.ide.spring.live;

import dtm.ide.spring.JavaType;
import dtm.ide.spring.SpringBean;
import dtm.ide.spring.SpringBeanTraits;
import dtm.ide.spring.SpringIndexSnapshot;
import dtm.ide.spring.SpringStereotype;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SpringRuntimeBeans {

    private SpringRuntimeBeans() {
    }

    public static List<SpringBean> from(List<SpringActuatorClient.LiveBean> liveBeans,
                                        SpringIndexSnapshot snapshot) {
        if (liveBeans == null || liveBeans.isEmpty()) {
            return List.of();
        }
        List<SpringBean> beans = new ArrayList<>();
        for (SpringActuatorClient.LiveBean live : liveBeans) {
            if (live == null || live.type() == null || live.type().isBlank()) {
                continue;
            }
            Optional<JavaType> declared = snapshot == null
                    ? Optional.empty()
                    : snapshot.typeGraph().byQualifiedName(live.type());
            if (declared.isPresent()) {
                continue;
            }
            Path file = null;
            int line = 1;
            beans.add(new SpringBean(
                    live.name(),
                    live.type(),
                    live.simpleType(),
                    SpringStereotype.COMPONENT,
                    file,
                    line,
                    List.of(),
                    List.of(),
                    false,
                    "",
                    false,
                    new SpringBeanTraits(live.scope(), false, null, live.dependencies(), List.of()),
                    SpringBean.Origin.RUNTIME));
        }
        return List.copyOf(beans);
    }
}
