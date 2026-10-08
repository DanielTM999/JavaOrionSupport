package dtm.ide;

import dtm.ide.adapter.AdapterHost;
import dtm.ide.api.extension.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class JavaIdeAdapterProxyHostTest {

    @TempDir
    Path resources;

    @Test
    void hostFollowsThePlatformProxyInsteadOfTheCopiedInstance() throws Exception {
        Resource resource = (Resource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Resource.class},
                (instance, method, args) -> method.getName().equals("getResourcePath") ? resources : null);
        JavaIdeAdapter real = new JavaIdeAdapter();
        JavaIdeAdapter proxy = new JavaIdeAdapter() {
            @Override
            public Resource getResource() {
                return resource;
            }
        };
        copyState(real, proxy);
        AdapterHost host = (AdapterHost) field("adapterHost").get(proxy);

        proxy.getSettingsPages();

        assertSame(proxy, host.monitor());
        assertSame(resource, host.resource());
        assertNotNull(host.currentSettings());
        assertSame(field("settings").get(proxy), host.currentSettings());
    }

    private static void copyState(JavaIdeAdapter from, JavaIdeAdapter to) throws IllegalAccessException {
        for (Class<?> type = JavaIdeAdapter.class; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                field.set(to, field.get(from));
            }
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = JavaIdeAdapter.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
