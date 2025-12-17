package com.github.Dfpello;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Mojo(name = "list-smells", defaultPhase = LifecyclePhase.TEST, threadSafe = true)

public class TestSmellDetectorMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File targetDir;
    
    private static class TestReport {
        String testName;
        Map<String, Integer> smells = new HashMap<>();

        TestReport(String testName) {
            this.testName = testName;
        }
    }
    
    @Override
    public void execute() throws MojoExecutionException {
        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            File inputPathsCsv = generateInputFile();
            File outputCsv = runTsDetect(inputPathsCsv);
            List<TestReport> reports = parseCsv(outputCsv);
            printReport(reports);

        } catch (Exception e) {
            throw new MojoExecutionException("Error ejecutando TestSmellDetector", e);
        }
    }
    
    private File generateInputFile() throws IOException {
        File testSourceDir = new File(baseDir, "src" + File.separator + "test" + File.separator + "java");
        File mainSourceDir = new File(baseDir, "src" + File.separator + "main" + File.separator + "java");
        File inputCsv = new File(targetDir, "tsDetect-input.csv");

        if (!testSourceDir.exists()) {
            getLog().warn("No se encontró la carpeta src/test/java");
            return inputCsv;
        }

        List<Path> testFiles;
        try (Stream<Path> walk = Files.walk(testSourceDir.toPath())) {
            testFiles = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(inputCsv))) {
            
            for (Path testPath : testFiles) {
                String testAbsPath = testPath.toAbsolutePath().toString().replace(File.separator, "/");
                String prodAbsPath;
                
                Path testRelativePath = testSourceDir.toPath().relativize(testPath);
                Path expectedProdPath = mainSourceDir.toPath().resolve(testRelativePath);
                
                String prodPathString = expectedProdPath.toAbsolutePath().toString();
                
                if (prodPathString.endsWith("Test.java")) {
                    prodPathString = prodPathString.substring(0, prodPathString.length() - 9) + ".java";
                }
                
                File actualProdFile = new File(prodPathString);

                if (actualProdFile.exists()) {
                    prodAbsPath = actualProdFile.getAbsolutePath().replace(File.separator, "/");
                } else {
                    prodAbsPath = ""; 
                    getLog().warn("Clase de producción no encontrada para " + testPath.getFileName());
                }

                writer.printf("App,%s,%s%n", testAbsPath, prodAbsPath);
            }
        }
        
        getLog().info("Archivo de entrada generado en " + inputCsv.getAbsolutePath());
        return inputCsv;
    }
    
    private File runTsDetect(File inputPathsCsv) throws IOException, InterruptedException, MojoExecutionException {

        File tsDetectJar = locateTsDetectJar(); 
        
        List<String> command = List.of("java", "-jar", tsDetectJar.getAbsolutePath(), inputPathsCsv.getAbsolutePath(), targetDir.getAbsolutePath());

        getLog().info("Ejecutando tsDetect...");
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("tsDetect finalizó con código " + exitCode + ".");
        }
        
        getLog().info("Buscando archivo de salida...");
        File finalOutput = new File(targetDir, "test-smells-report.csv");
        Path found = null;
        
        try (Stream<Path> files = Files.list(baseDir.toPath())) {
            found = files.filter(p -> p.getFileName().toString().startsWith("Output_TestSmellDetection_") && p.getFileName().toString().endsWith(".csv")).findFirst().orElse(null);
        } catch (Exception ignored) {
            getLog().warn("Fallo al listar el directorio raíz.");
        }
        
        if (found != null) {
            Files.move(found, finalOutput.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return finalOutput;
        }
        
        throw new IOException("tsDetect no generó el archivo de salida dinámico (Output_TestSmellDetection_*.csv).");
    }

    private File locateTsDetectJar() throws MojoExecutionException, IOException {
        String resourceName = "/tsDetect.jar";
        InputStream jarStream = getClass().getResourceAsStream(resourceName);

        if (jarStream == null) {
            throw new MojoExecutionException("No se encontró " + resourceName + " en resources.");
        }

        File tempJar = new File(targetDir, "tsDetect-extracted.jar");
        Files.copy(jarStream, tempJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return tempJar;
    }
    
    private List<TestReport> parseCsv(File csvFile) throws IOException {
        List<TestReport> reports = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return reports;

            String[] headers = headerLine.split(",");
            List<Integer> smellColumns = detectSmellColumns(headers);

            String line;
            while ((line = reader.readLine()) != null) {
                String[] row = line.split(",", -1);
                if (row.length < 3) continue; 
                
                String testName = new File(row[1]).getName();
                if (testName.isEmpty() && row.length > 2) {
                    testName = new File(row[2]).getName();
                }
                if (testName.isEmpty()){
                    testName = "Sin Nombre";
                }
                TestReport report = new TestReport(testName);

                for (int idx : smellColumns) {
                    if (idx < row.length && !row[idx].isEmpty()) {
                        try {
                            double valDouble = Double.parseDouble(row[idx]); 
                            int value = (int) Math.round(valDouble);
                            if (value > 0) {
                                report.smells.put(headers[idx], value);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                reports.add(report);
            }
        }
        return reports;
    }

    private List<Integer> detectSmellColumns(String[] headers) {
        List<Integer> cols = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].toLowerCase();
            if (!h.contains("path") && !h.contains("class") && !h.contains("app") && !h.contains("numberofmethods")) {
                cols.add(i);
            }
        }
        return cols;
    }

    private void printReport(List<TestReport> reports) {
        getLog().info("-------------------------------------------------------");
        getLog().info("RESULTADO DEL ANÁLISIS DE TEST SMELLS");
        getLog().info("-------------------------------------------------------");

        if (reports.isEmpty()) {
             getLog().warn("El analisis no saco resultados");
        }

        for (TestReport r : reports) {
            getLog().info(r.testName);
            if (r.smells.isEmpty()) {
                getLog().info("Sin Test Smells detectados");
            } else {
                r.smells.forEach((k, v) -> getLog().info(k + ": " + v));
            }
        }
        getLog().info("-------------------------------------------------------");
    }
}