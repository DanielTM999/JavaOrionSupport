package dtm.ide.spring;

import dtm.ide.api.search.GlobalSearchMatch;
import dtm.ide.spring.jpa.JpaEntity;
import dtm.ide.spring.jpa.JpaRepositoryInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpringSearchContributor {

    private static final int MAX_MATCHES = 50;

    private SpringSearchContributor() {
    }

    public static List<GlobalSearchMatch> search(SpringIndexSnapshot snapshot, String term) {
        if (snapshot == null || term == null || term.isBlank()) {
            return List.of();
        }
        String needle = term.trim().toLowerCase(Locale.ROOT);
        List<GlobalSearchMatch> matches = new ArrayList<>();

        for (SpringEndpoint endpoint : snapshot.sortedEndpoints()) {
            if (matches.size() >= MAX_MATCHES) {
                return List.copyOf(matches);
            }
            String route = endpoint.method() + " " + endpoint.path();
            if (!route.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            matches.add(match(endpoint.file(), endpoint.line(), route + "  ->  "
                    + SpringBean.simpleNameOf(endpoint.handlerType()) + "."
                    + endpoint.handlerName()));
        }

        for (SpringBean bean : snapshot.beans()) {
            if (matches.size() >= MAX_MATCHES) {
                return List.copyOf(matches);
            }
            String label = bean.hasQualifier() ? bean.qualifier() : bean.name();
            if (!bean.simpleName().toLowerCase(Locale.ROOT).contains(needle)
                    && !label.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            matches.add(match(bean.file(), bean.line(),
                    "@" + bean.stereotype().name().toLowerCase(Locale.ROOT) + " "
                            + bean.simpleName() + "  (" + label + ")"));
        }

        for (JpaEntity entity : snapshot.entities()) {
            if (matches.size() >= MAX_MATCHES) {
                return List.copyOf(matches);
            }
            if (!entity.simpleName().toLowerCase(Locale.ROOT).contains(needle)
                    && !entity.effectiveTable().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            matches.add(match(entity.file(), entity.line(),
                    "@entity " + entity.simpleName() + "  (" + entity.effectiveTable() + ")"));
        }

        for (JpaRepositoryInfo repository : snapshot.repositories()) {
            if (matches.size() >= MAX_MATCHES) {
                return List.copyOf(matches);
            }
            if (!repository.simpleName().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            matches.add(match(repository.file(), repository.line(),
                    "@repository " + repository.simpleName() + "  ("
                            + repository.entitySimpleName() + ")"));
        }
        return List.copyOf(matches);
    }

    private static GlobalSearchMatch match(java.nio.file.Path file, int line, String preview) {
        return GlobalSearchMatch.content(file, Math.max(0, line - 1), 0, 0, preview);
    }
}
