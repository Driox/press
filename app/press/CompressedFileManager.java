package press;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import play.Play;
import press.io.CompressedFile;

public abstract class CompressedFileManager {
	private PressFileWriter pressFileWriter;
	private Compressor compressor;

	public CompressedFileManager(Compressor compressor) {
		this.compressor = compressor;
		this.pressFileWriter = new PressFileWriter(compressor);
	}

	/**
	 * Get the compressed file with the given compression key
	 */
	public CompressedFile getCompressedFile(String key) {
		List<FileInfo> componentFiles = SourceFileManager.getSourceFiles(key);

		// If there was nothing found for the given request key, return null.
		// This shouldn't happen unless there was a very long delay between the
		// template being rendered and the compressed file being requested
		if (componentFiles == null) {
			return null;
		}

		return getCompressedFile(componentFiles);
	}

	public CompressedFile getCompressedFileFromConfig(String key, String type) {
		List<FileInfo> componentFiles = buildDefaultFileInfo(key, type);

		// If there was nothing found for the given request key, return null.
		// This shouldn't happen unless there was a very long delay between the
		// template being rendered and the compressed file being requested
		if (componentFiles == null) {
			return null;
		}

		return getCompressedFile(componentFiles);
	}

	private List<FileInfo> buildDefaultFileInfo(String key, String type) {
		SourceFileManager manager;
		if (type.equals("css")) {
			manager = new StyleFileManager();
		} else if (type.equals("js")) {
			manager = new ScriptSourceFileManager();
		} else {
			return null;
		}

		boolean compress = PluginConfig.enabled;
		List<FileInfo> file_infos = new ArrayList();
		for (String file_name : loadFiles(key)) {
			file_infos.add(new FileInfo(compress, manager.checkFileExists(file_name)));
		}

		return file_infos;
	}

	private List<String> loadFiles(String type) {
		return Arrays.asList(play.vfs.VirtualFile.fromRelativePath("/conf/" + type).contentAsString().split("\n"));
	}

	/**
	 * Get the compressed file for the given set of component files
	 */
	public CompressedFile getCompressedFile(List<FileInfo> componentFiles) {
		// First check if the compressor has a cached copy of the file
		String key = compressor.getCompressedFileKey(componentFiles);
		CompressedFile file = CompressedFile.create(key, getCompressedDir());
		if (CacheManager.useCachedFile(file)) {
			return file;
		}

		// If there is no cached file, generate one
		return pressFileWriter.writeCompressedFile(componentFiles, file);
	}

	public abstract String getCompressedDir();
}
