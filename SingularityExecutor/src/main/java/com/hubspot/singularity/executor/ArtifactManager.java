package com.hubspot.singularity.executor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.hubspot.deploy.ExternalArtifact;
import com.hubspot.singularity.executor.config.SingularityExecutorConfiguration;

public class ArtifactManager {

  private final Path cacheDirectory;
  private final Path executorOut;
  private final Logger log;
  private final String taskId;
  
  private final Lock processLock;
  private volatile Optional<String> currentProcessCmd;
  private volatile Optional<Process> currentProcess;
    
  public ArtifactManager(SingularityExecutorConfiguration configuration, String taskId, Logger log) {
    this.cacheDirectory = Paths.get(configuration.getCacheDirectory());
    this.executorOut = configuration.getExecutorBashLogPath(taskId);
    this.log = log;
    this.taskId = taskId;
    
    this.currentProcessCmd = Optional.absent();
    this.currentProcess = Optional.absent();
    
    this.processLock = new ReentrantLock();
  }

  public void destroyProcessIfActive() {
    this.processLock.lock();
    
    try {
      if (currentProcess.isPresent()) {
        log.info("Destroying a process {}", currentProcessCmd);
        
        currentProcess.get().destroy();
        
        clearCurrentProcessUnsafe();
      }
    } finally {
      this.processLock.unlock();
    }
  }
  
  private void clearCurrentProcessUnsafe() {
    currentProcess = Optional.absent();
    currentProcessCmd = Optional.absent();
  }
  
  private long getSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException ioe) {
      throw new RuntimeException(String.format("Couldnt get file size of %s", path), ioe);
    }
  }
  
  private boolean filesSizeMatches(ExternalArtifact artifact, Path path) {
    return artifact.getFilesize() < 1 || artifact.getFilesize() == getSize(path);
  }
  
  private boolean md5Matches(ExternalArtifact artifact, Path path) {
    return !artifact.getMd5sum().isPresent() || artifact.getMd5sum().get().equals(calculateMd5sum(path));
  }
  
  private void checkFilesize(ExternalArtifact artifact, Path path) {
    if (!filesSizeMatches(artifact, path)) {
      throw new RuntimeException(String.format("Filesize %s (%s) does not match expected (%s)", getSize(path), path, artifact.getFilesize()));
    }

    if (!md5Matches(artifact, path)) {
      throw new RuntimeException(String.format("Md5sum %s (%s) does not match expected (%s)", calculateMd5sum(path), path, artifact.getMd5sum().get()));
    }
  }
  
  private Path createTempPath(String filename) {
    try {
      return Files.createTempFile(filename, null);
    } catch (IOException e) {
      throw new RuntimeException(String.format("Couldn't create temporary file for %s", filename), e);
    }
  }
  
  public void downloadAndCheck(ExternalArtifact artifact, Path downloadTo) {
    downloadUri(artifact.getUrl(), downloadTo);
    
    checkFilesize(artifact, downloadTo);
  }
  
  private Path downloadAndCache(ExternalArtifact artifact, String filename) {
    Path tempFilePath = createTempPath(filename);
    
    downloadAndCheck(artifact, tempFilePath);
    
    Path cachedPath = getCachedPath(filename);
    
    try {
      Files.move(tempFilePath, cachedPath, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new RuntimeException(String.format("Couldn't move %s to %s", tempFilePath, cachedPath), e);
    }
    
    return cachedPath;
  }
  
  private Path getCachedPath(String filename) {
    return cacheDirectory.resolve(filename);
  }
  
  private boolean checkCached(ExternalArtifact artifact, Path cachedPath) {
    if (!Files.exists(cachedPath)) {
      log.debug("Cached {} did not exist", taskId, cachedPath);
      return false;
    }
    
    if (!filesSizeMatches(artifact, cachedPath)) {
      log.debug("Cached {} ({}) did not match file size {}", cachedPath, getSize(cachedPath), artifact.getFilesize());
      return false;
    }
    
    if (!md5Matches(artifact, cachedPath)) {
      log.debug("Cached {} ({}) did not match md5 {}", cachedPath, calculateMd5sum(cachedPath), artifact.getMd5sum().get());
      return false;
    }
    
    return true;
  }
  
  public Path fetch(ExternalArtifact artifact) {
    String filename = getFilenameFromUri(artifact.getUrl());
    Path cachedPath = getCachedPath(filename);
    
    if (!checkCached(artifact, cachedPath)) {
      downloadAndCache(artifact, filename);
    } else {
      log.info("Using cached file {}", getFullPath(cachedPath));
    }
  
    return cachedPath;
  }
  
  private void setCurrentProcess(Optional<String> newProcessCmd, Optional<Process> newProcess) {
    try {
      processLock.lockInterruptibly();
    } catch (InterruptedException e) {
      throw Throwables.propagate(e);
    } 
    
    try {
      currentProcessCmd = newProcessCmd;
      currentProcess = newProcess;
    } finally {
      processLock.unlock();
    }
  }
  
  private void runCommand(final List<String> command) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);

    try {
      final File outputFile = executorOut.toFile();
      processBuilder.redirectError(outputFile);
      processBuilder.redirectOutput(outputFile);
      
      final Process process = processBuilder.start();
      
      setCurrentProcess(Optional.of(command.get(0)), Optional.of(process));
      
      final int exitCode = process.waitFor();
        
      Preconditions.checkState(exitCode == 0, "Got exit code %d while running command %s", exitCode, command);

      setCurrentProcess(Optional.<String> absent(), Optional.<Process> absent());

    } catch (Throwable t) {
      throw new RuntimeException(String.format("While running %s", command), t);
    }
  }
  
  private String getFullPath(Path path) {
    return path.toAbsolutePath().toString();
  }
  
  private void downloadUri(String uri, Path path) {
    log.info("Downloading {} to {}", uri, getFullPath(path));

    final List<String> command = Lists.newArrayList();
    command.add("wget");
    command.add(uri);
    command.add("-O");
    command.add(getFullPath(path));
    command.add("-nv");
    command.add("--no-check-certificate");

    runCommand(command);
  }

  public void untar(Path source, Path destination) {
    log.info("Untarring {} to {}", getFullPath(source), getFullPath(destination));
    
    final List<String> command = Lists.newArrayList();
    command.add("tar");
    command.add("-oxzf");
    command.add(getFullPath(source));
    command.add("-C");
    command.add(getFullPath(destination));
  
    runCommand(command);
  }

  private String calculateMd5sum(Path path) {
    try {
      HashCode hc = com.google.common.io.Files.hash(path.toFile(), Hashing.md5());
      
      return hc.toString();
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  public static String getFilenameFromUri(String uriString) {
    final URI uri = URI.create(uriString);
    final String path = uri.getPath();
    int lastIndexOf = path.lastIndexOf("/");
    if (lastIndexOf < 0) {
      return path;
    }
    return path.substring(lastIndexOf + 1);
  }

}
