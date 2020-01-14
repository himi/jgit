package org.eclipse.jgit.vfs;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineIn;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.util.FS;

/**
 * @author himi
 *
 */
public class RemoteObjectResolver {
	private static final String readObjectHookName = "read-object.exe"; //$NON-NLS-1$

    private static class ReadObjectProcess {
		private static final byte[] welcome = "git-read-object-client\n".getBytes(); //$NON-NLS-1$

		private static final byte[] version = "version=1\n".getBytes(); //$NON-NLS-1$

		private static final byte[] capability = "capability=get\n".getBytes(); //$NON-NLS-1$

		private static final byte[] commandGet = "command=get\n".getBytes(); //$NON-NLS-1$

        private static final byte[] sha1Header = "sha1=".getBytes(); //$NON-NLS-1$

        private static byte[] initSHA1Message() {
            byte[] ret = new byte[sha1Header.length + Constants.OBJECT_ID_STRING_LENGTH];
			System.arraycopy(sha1Header, 0, ret, 0, sha1Header.length);
			return ret;
        }

        private final byte[] sha1Message = initSHA1Message();

        private final PacketLineIn in;
        private final PacketLineOut out;

		private void packetWrite(byte[]... messages) throws IOException {
            for (byte[] mes : messages) {
                out.writePacket(mes);
            }
            out.end();
        }

        private boolean matchRead(String match) {
			String str;
			try {
				do {
					str = in.readString();
				} while (str.length() == 0);
			} catch (IOException e) {
				return false;
			}
            return match.equals(str);
        }

		private void greeting() throws IOException {
            packetWrite(welcome, version);
			matchRead("git-read-object-server"); //$NON-NLS-1$
			matchRead("version=1"); //$NON-NLS-1$
            packetWrite(capability);
			matchRead("capability=get"); //$NON-NLS-1$
        }

        public boolean get(AnyObjectId objectId) {
            objectId.copyTo(sha1Message, sha1Header.length);
			try {
				packetWrite(commandGet, sha1Message);
			} catch (IOException e) {
				return false;
			}
			return matchRead("status=success"); //$NON-NLS-1$
        }

		ReadObjectProcess(Process proc) throws IOException {
            this.in = new PacketLineIn(proc.getInputStream());
            this.out = new PacketLineOut(proc.getOutputStream());
			greeting();
        }
    }

    private static final String[] readObjectArgs = {};

	private static ReadObjectProcess initReadObjectProcess(
			Repository repository)
			throws IOException {
		final File hookFile = FS.DETECTED.findHook(repository, readObjectHookName);
		if (hookFile == null) return null;

		final String hookPath = hookFile.getAbsolutePath();
		final File runDirectory;
		if (repository.isBare()) {
			runDirectory = repository.getDirectory();
        } else {
			runDirectory = repository.getWorkTree();
        }
		final String cmd = FS.DETECTED
				.relativize(runDirectory.getAbsolutePath(), hookPath);
		ProcessBuilder hookProcess = FS.DETECTED.runInShell(cmd,
				readObjectArgs);
		hookProcess.directory(runDirectory);

		Map<String, String> environment = hookProcess.environment();
		environment.put(Constants.GIT_DIR_KEY,
                        repository.getDirectory().getAbsolutePath());
		if (!repository.isBare()) {
			environment.put(Constants.GIT_WORK_TREE_KEY,
                            repository.getWorkTree().getAbsolutePath());
		}

        Process proc = hookProcess.start();
        return new ReadObjectProcess(proc);
                                     /*
		try {
			return runProcess(hookProcess, outRedirect,
                              errRedirect, stdinArgs);
		} catch (InterruptedException e) {
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().exceptionHookExecutionInterrupted,
							hookName), e);
		}
                                     */
    }

    private final ReadObjectProcess readObjectProcess;

	/**
	 * @param objectId
	 * @return boolean
	 */
    public boolean readObject(AnyObjectId objectId) {
		if (readObjectProcess == null)
			return false;
        return readObjectProcess.get(objectId);
    }

	/**
	 * @param repository
	 * @throws IOException
	 */
    public RemoteObjectResolver(Repository repository) throws IOException {
		this.readObjectProcess = initReadObjectProcess(repository);
    }
}
