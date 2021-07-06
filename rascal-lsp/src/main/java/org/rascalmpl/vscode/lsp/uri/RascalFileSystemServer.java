package org.rascalmpl.vscode.lsp.uri;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.FileAlreadyExistsException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.stream.Stream;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.rascalmpl.library.Prelude;
import org.rascalmpl.uri.ISourceLocationWatcher.ISourceLocationChangeType;
import org.rascalmpl.uri.ISourceLocationWatcher.ISourceLocationChanged;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.uri.URIUtil;
import org.rascalmpl.values.IRascalValueFactory;
import org.rascalmpl.vscode.lsp.uri.IRascalFileSystemServer.FileStat;
import org.rascalmpl.vscode.lsp.uri.IRascalFileSystemServer.FileType;
import org.rascalmpl.vscode.lsp.uri.IRascalFileSystemServer.FileWithType;
import org.rascalmpl.vscode.lsp.uri.IRascalFileSystemServer.LocationContent;

import io.usethesource.vallang.ISourceLocation;

public class RascalFileSystemServer implements IRascalFileSystemServer {
    private final URIResolverRegistry reg = URIResolverRegistry.getInstance();
    private static final Logger logger = LogManager.getLogger(RascalFileSystemServer.class);

    @Override
    public CompletableFuture<Void> watch(WatchParameters params) {
        return CompletableFuture.runAsync(() -> {
            try {
                ISourceLocation loc = params.getLocation();

                URIResolverRegistry.getInstance().watch(loc, params.isRecursive(), changed -> {
                    try {
                        onDidChangeFile(convertChangeEvent(changed));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (IOException | URISyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static FileChangeEvent convertChangeEvent(ISourceLocationChanged changed) throws IOException {
        return new FileChangeEvent(convertFileChangeType(changed.getChangeType()), changed.getLocation().getURI().toASCIIString());
    }

    private static FileChangeType convertFileChangeType(ISourceLocationChangeType changeType) throws IOException {
        switch (changeType) {
            case CREATED:
                return FileChangeType.Created;
            case DELETED:
                return FileChangeType.Deleted;
            case MODIFIED:
                return FileChangeType.Changed;
            default:
                throw new IOException("unknown change type: " + changeType);
        }
    }

    @Override
    public CompletableFuture<FileStat> stat(URIParameter uri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ISourceLocation loc = uri.getLocation();
                return new FileStat(
                    reg.isDirectory(loc) ? FileType.Directory : FileType.File,
                    reg.created(loc),
                    reg.lastModified(loc),
                    reg.supportsReadableFileChannel(loc)
                        ? reg.getReadableFileChannel(loc).size()
                        : Prelude.__getFileSize(IRascalValueFactory.getInstance(), loc).longValue());
            } catch (IOException | URISyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<FileWithType[]> readDirectory(URIParameter uri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ISourceLocation loc = uri.getLocation();
                return Arrays.stream(reg.list(loc))
                    .map(l -> new FileWithType(
                        URIUtil.getLocationName(l),
                        reg.isDirectory(l) ? FileType.Directory : FileType.File)
                    )
                    .toArray(FileWithType[]::new);
            } catch (IOException | URISyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> createDirectory(URIParameter uri) {
        return CompletableFuture.runAsync(() -> {
            try {
                ISourceLocation loc = uri.getLocation();
                reg.mkDirectory(loc);
            } catch (IOException | URISyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static final int BUFFER_SIZE = 3 * 1024; // has to be divisibly by 3
    @Override
    public CompletableFuture<LocationContent> readFile(URIParameter uri) {
        return CompletableFuture.supplyAsync(() -> {
            logger.trace("Reading file: {}", uri.getUri());
            try (InputStream source = reg.getInputStream(uri.getLocation())){
                // there is no streaming base64 encoder, but we also do not want to have the whole file in memory
                // just to base64 encode it. So we stream it in chunks that will not cause padding characters in
                // base 64
                Encoder encoder = Base64.getEncoder();
                StringBuilder result = new StringBuilder();
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = source.read(buffer, 0, BUFFER_SIZE)) == BUFFER_SIZE) {
                    result.append(encoder.encodeToString(buffer));
                }
                if (read > 0) {
                    // last part needs to be a truncated part of the buffer
                    buffer = Arrays.copyOf(buffer, read);
                    result.append(encoder.encodeToString(buffer));
                }
                return new LocationContent(result.toString());
            } catch (IOException | URISyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> writeFile(WriteFileParameters params) {
        return CompletableFuture.runAsync(() -> {
            try {
                ISourceLocation loc = params.getLocation();

                boolean fileExists = reg.exists(loc);
                if (!fileExists && !params.isCreate()) {
                    throw new FileNotFoundException(loc.toString());
                }

                ISourceLocation parentFolder = URIUtil.getParentLocation(loc);
                if (!reg.exists(parentFolder) && params.isCreate()) {
                    throw new FileNotFoundException(parentFolder.toString());
                }

                if (fileExists && params.isCreate() && !params.isOverwrite()) {
                    throw new FileAlreadyExistsException(loc.toString());
                }
                try (OutputStream target = reg.getOutputStream(loc, false)) {
                    target.write(Base64.getDecoder().decode(params.getContent()));
                }
            } catch (IOException | URISyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> delete(DeleteParameters params) {
        return CompletableFuture.runAsync(() -> {
            try {
                ISourceLocation loc = params.getLocation();
                reg.remove(loc, params.isRecursive());
            } catch (IOException | URISyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> rename(RenameParameters params) {
        return CompletableFuture.runAsync(() -> {
            try {
                ISourceLocation oldLoc = params.getOldLocation();
                ISourceLocation newLoc = params.getNewLocation();
                reg.rename(oldLoc, newLoc, params.isOverwrite());
            }
            catch (IOException | URISyntaxException e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<String[]> fileSystemSchemes() {
        Set<String> inputs = reg.getRegisteredInputSchemes();
        Set<String> logicals = reg.getRegisteredLogicalSchemes();

        return CompletableFuture.completedFuture(
            Stream.concat(inputs.stream(), logicals.stream()).toArray(String[]::new)
        );
    }

    public static void main(String[] args) {
        RascalFileSystemServer server = new RascalFileSystemServer();

        Launcher<Void> clientLauncher = new Launcher.Builder<Void>()
            .setLocalService(server)
            .setInput(System.in)
            .setOutput(System.out)
            .create();

        try {
            clientLauncher.startListening().get();
        } catch (InterruptedException e) {
            logger.trace("Interrupted RascalFileSystemServer", e);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            logger.fatal("Unexpected exception", e.getCause());
            System.exit(1);
        }
    }
}
