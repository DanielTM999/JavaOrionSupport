package dtm.ide.build;

import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.sdk.BuildToolProvisioner;
import dtm.ide.sdk.DownloadProgressListener;
import dtm.ide.sdk.JdkInstallation;

import java.util.function.Supplier;

public final class BuildSystems {

    private BuildSystems() {
    }

    public static BuildSystem forProject(JavaProjectDescriptor descriptor,
                                         BuildToolProvisioner provisioner,
                                         Supplier<JdkInstallation> jdkSupplier,
                                         DownloadProgressListener progressListener) {
        if (descriptor == null) {
            return null;
        }
        if (descriptor.isMaven()) {
            return new MavenBuildService(descriptor, provisioner, jdkSupplier, progressListener);
        }
        if (descriptor.isGradle()) {
            return new GradleBuildService(descriptor, provisioner, jdkSupplier, progressListener);
        }
        return new JavacBuildService(descriptor, jdkSupplier);
    }
}
