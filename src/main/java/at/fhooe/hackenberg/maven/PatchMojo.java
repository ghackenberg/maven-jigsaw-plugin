package at.fhooe.hackenberg.maven;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@Mojo(name = "patch", defaultPhase = LifecyclePhase.PACKAGE)
public class PatchMojo extends BaseMojo {
	
	@Parameter
	private boolean ignoreMissingDeps;
	
	@Parameter
	private String multiRelease;

	@Override
	protected final void run() throws MojoExecutionException, MojoFailureException {
		
		File[] jars = modulePath.listFiles(file -> !file.isDirectory() && file.getName().endsWith(".jar"));
		
		for (File jar : jars) {
			try {
				// Is modular?
				
				System.out.println("[" + jar.getName() + "] Checking module info");
				
				try (ZipFile zip = new ZipFile(jar)) {
					if (zip.getEntry("module-info.class") != null) {
						continue;
					}
					if (zip.getEntry("META-INF/versions/") != null) {
						continue;
					}
				}
				
				// Define folders
				
				File temp = new File(jar.getParentFile(), jar.getName().substring(0, jar.getName().length() - 4));
				
				File sources = new File(temp, "sources");
				File classes = new File(temp, "classes");
				
				// Generate module info
				
				System.out.println("[" + jar.getName() + "] Generating module info");
				
				{
					List<String> command = new ArrayList<>();
					
					command.add(tool("jdeps"));
					
					if (ignoreMissingDeps) {
						command.add("--ignore-missing-deps");
					}
					if (multiRelease != null) {
						command.add("--multi-release");
						command.add(multiRelease);
					}
					
					command.add("--module-path");
					command.add(modulePath.getAbsolutePath());
					command.add("--generate-module-info");
					command.add(sources.getAbsolutePath());
					command.add(jar.getAbsolutePath());
					
					exec(command, "[" + jar.getName() + "] Jdeps did not terminate successfully!");
				}
				
				// Unzip jar
				
				System.out.println("[" + jar.getName() + "] Unzipping");

				try (ZipFile zip = new ZipFile(jar)) {
					Enumeration<? extends ZipEntry> entries = zip.entries();
					
					while (entries.hasMoreElements()) {
						ZipEntry entry = entries.nextElement();
						
						File destination = new File(classes, entry.getName());
						
						destination.getParentFile().mkdirs();
						
						if (entry.isDirectory()) {
							destination.mkdir();
						} else {
							BufferedInputStream input = new BufferedInputStream(zip.getInputStream(entry));
							BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination));
							
							output.write(input.readAllBytes());
							
							output.close();
							input.close();
						}
					}
				}
				
				// Compile and package module info
				
				for (File module : sources.listFiles()) {
					
					File versions = new File(module, "versions");
					
					for (File version : versions.listFiles()) {
						
						// Compile module info
						
						File moduleInfoSource = new File(version, "module-info.java");
						
						System.out.println("[" + jar.getName() + "] Compiling " + moduleInfoSource.getName());
						
						List<String> command = new ArrayList<>();
						
						command.add(tool("javac"));
						
						command.add("--module-path");
						command.add(modulePath.getAbsolutePath());
						
						command.add("-d");
						command.add(classes.getAbsolutePath());
						
						command.add(moduleInfoSource.getAbsolutePath());
						
						exec(command, "[" + jar.getName() + "] Javac did not terminate successfully!");
						
						// Package module info
						
						File moduleInfoClass = new File(classes, "module-info.class");
						
						System.out.println("[" + jar.getName() + "] Packaging " + moduleInfoClass.getName());
						
						FileSystem fileSystem = FileSystems.newFileSystem(jar.toPath(), new HashMap<>());
						
						Path path = fileSystem.getPath("module-info.class");

						BufferedInputStream input = new BufferedInputStream(new FileInputStream(moduleInfoClass));
						BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE));
						
						output.write(input.readAllBytes());
						
						output.close();
						input.close();
						
						fileSystem.close();
						
					}
				}
			} catch (Exception e) {
				System.err.println(e.getLocalizedMessage());
			}
		}
	}

}