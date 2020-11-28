package software.chronicle;

import org.apache.maven.model.Build;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import static java.lang.Double.parseDouble;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;

@Mojo(name = "enforcer", defaultPhase = LifecyclePhase.VERIFY)
public class BinaryCompatibilityEnforcerPluginMogo extends AbstractMojo {

    public static final String REPORT = "Report: ";
    public static final String BINARY_COMPATIBILITY = "Binary compatibility: ";
    public static final String BAR = "\n------------------------------" +
            "------------------------------------------\n";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "japi-compliance-checker.pl -lib NAME %s %s", readonly = true, required = false)
    private String expression;

    @Parameter(defaultValue = "", required = false)
    private String referenceVersion;

    @Parameter(defaultValue = "100.0", required = false)
    double binaryCompatibilityPercentageRequired;


    public void execute() throws MojoExecutionException {

        getLog().info(String.format("%s\nBINARY COMPATIBILITY ENFORCER\n%s", BAR, BAR));

        getLog().info("Starting...");

        try {
            checkJavaAPIComplianceCheckerInstalled();
        } catch (MojoExecutionException e) {
            getLog().warn(e.getMessage(), e);
            return;
        }

        final Build build = project.getBuild();
        if (build == null) {
            throw new MojoExecutionException("build not found");
        }

        final String groupId = project.getGroupId();
        final String artifactId = project.getArtifactId();
        final String outputDirectory = build.getOutputDirectory();

        getLog().info(referenceVersion);

        String pathToJar1 = null;
        if (referenceVersion == null || referenceVersion.isEmpty()) {

            String version = project.getVersion();
            int i = version.indexOf(".");
            if (i != -1) {
                i = version.indexOf(".", i + 1);
                if (i != -1) {
                    version = version.substring(0, i);
                    referenceVersion = version + ".0";
                    getLog().info("setting referenceVersion=" + referenceVersion);
                    try {
                        pathToJar1 = downloadArtifact(groupId,
                                artifactId,
                                referenceVersion,
                                outputDirectory).getAbsolutePath();
                    } catch (Exception e) {
                        throw new MojoExecutionException("Please set <referenceVersion> config, can not download default version=" + referenceVersion + " of " + artifactId, e);
                    }

                }

                if (pathToJar1 == null)
                    throw new MojoExecutionException("Please set <referenceVersion> config");
            } else {

                pathToJar1 = downloadArtifact(groupId,
                        artifactId,
                        referenceVersion,
                        outputDirectory).getAbsolutePath();
            }

        } else
            pathToJar1 = downloadArtifact(groupId,
                    artifactId,
                    referenceVersion,
                    outputDirectory).getAbsolutePath();

        getLog().debug("pathToJar1=" + pathToJar1);

        final String directory = build.getDirectory();

        final String finalName = project.getBuild().getFinalName();
        final String pathToJar2 = format("%s%s%s.jar", directory, File.separator, finalName);

        getLog().debug("pathToJar2=" + pathToJar2);

        if (finalName.endsWith("0-SNAPSHOT.jar") || finalName.endsWith("0.jar"))
            return;

        checkBinaryCompatibility(pathToJar1, pathToJar2);
    }


    private File downloadArtifact(final String group, final String artifactId, final String version, String target) throws MojoExecutionException {
        final File tempFile = new File(target, artifactId + "-" + version + ".jar");
        tempFile.delete();
        final String command = String.format("mvn dependency:get -Dartifact=%s:%s:%s:jar -Dtransitive=false -Ddest=%s -DremoteRepositories=chronicle-enterprise-release::::https://nexus.chronicle.software/content/repositories/releases ", group, artifactId, version, tempFile);

        getLog().info(command);

        BufferedReader stdError = null;
        Process p = null;
        try {
            p = getRuntime().exec(command);

            final BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(p.getInputStream()));

            stdError = new BufferedReader(new InputStreamReader(p.getErrorStream()));

            stdInput.readLine();

            for (; ; ) {
                final String s1 = stdInput.readLine();
                if (s1 == null) {
                    dumpErrorToConsole(stdError);
                    throw new MojoExecutionException("unable to download using command=" + command);
                }

                if (s1.contains("BUILD SUCCESS")) {
                    break;
                }

                getLog().debug(s1);
            }
            return tempFile;
        } catch (Exception e) {
            dumpErrorToConsole(stdError);
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            shutdown(p);
        }
    }


    public void checkBinaryCompatibility(final String jar1, final String jar2) throws MojoExecutionException {

        BufferedReader stdError = null;
        Process p = null;
        try {
            final String command = format(expression, jar1, jar2);

            getLog().debug("command=" + command);
            p = new ProcessBuilder("/bin/sh", "-c", command).start();

            final BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(p.getInputStream()));

            stdError = new BufferedReader(new
                    InputStreamReader(p.getErrorStream()));
            String binaryCompatibility = "";
            for (; ; ) {
                final String s1 = stdInput.readLine();
                if (s1 == null) {
                    dumpErrorToConsole(stdError);
                    throw new MojoExecutionException("failed to to successfully execute, command=" + command);
                }

                if (s1.startsWith("Binary compatibility: ")) {
                    assert s1.endsWith("%");
                    binaryCompatibility = s1.substring(BINARY_COMPATIBILITY.length(), s1.length() - 1);
                }

                if (!s1.startsWith(REPORT)) {
                    getLog().debug(s1);
                    continue;
                }

                final String report = s1.substring(REPORT.length());

                if (parseDouble(binaryCompatibility) < binaryCompatibilityPercentageRequired) {

                    throw new MojoExecutionException("\n" + BAR + "BINARY COMPATIBILITY ENFORCER - FAILURE: " + binaryCompatibility + "%  binary compatibility\nYour changes are only " +
                            binaryCompatibility + "% binary compatibility, this enforcer plugin requires at least " + binaryCompatibilityPercentageRequired + "% binary compatibility,\n between " + jar1 + " and " + jar2 + "\nsee report \"file://" + new File(report).getAbsolutePath() + "\"" + BAR);

                } else
                    getLog().info(String.format("Whilst checking against %s", jar1));
                getLog().info(String.format("%sBINARY COMPATIBILITY ENFORCER - SUCCESSFUL%s", BAR, BAR));
                return;

            }

        } catch (final IOException e) {
            dumpErrorToConsole(stdError);
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {

            shutdown(p);

        }
    }

    private void dumpErrorToConsole(final BufferedReader std) throws MojoExecutionException {
        if (std == null)
            return;
        try {
            for (String s1 = std.readLine(); s1 != null; s1 = std.readLine()) {
                getLog().warn(s1);
            }
        } catch (final IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }


    public void checkJavaAPIComplianceCheckerInstalled() throws MojoExecutionException {
        Process p = null;
        BufferedReader stdError = null;
        try {

            p = new ProcessBuilder("/bin/sh", "-c", "japi-compliance-checker.pl -l").start();
            final BufferedReader stdInput = new BufferedReader(new
                    InputStreamReader(p.getInputStream()));

            stdError = new BufferedReader(new
                    InputStreamReader(p.getErrorStream()));

            stdInput.readLine();
            final String firstLine = stdInput.readLine();

            if (!firstLine.startsWith("Java API Compliance Checker")) {
                throw new MojoExecutionException("Unable to load Java API Compliance Checker, please add it your $PATH and check it permissions.");
            }

            getLog().info("Java API Compliance Checker - correctly installed");

        } catch (final IOException | MojoExecutionException e) {
            dumpErrorToConsole(stdError);
            throw new MojoExecutionException(e.getMessage(), e);
        } finally {
            shutdown(p);
        }
    }

    private void shutdown(Process p) {
        if (p != null) {
            p.destroy();
            try {
                p.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                p.destroyForcibly();
            }
        }
    }

}