package controllers.press;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.zip.GZIPOutputStream;

import models.contextcss.ContextCss;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import play.exceptions.UnexpectedException;
import play.mvc.Controller;
import play.mvc.Finally;
import play.vfs.VirtualFile;
import press.CachingStrategy;
import press.PluginConfig;
import press.ScriptCompressedFileManager;
import press.ScriptRequestHandler;
import press.StyleCompressedFileManager;
import press.StyleRequestHandler;
import press.io.CompressedFile;
import press.io.FileIO;

public class Press extends Controller {
	public static final DateTimeFormatter httpDateTimeFormatter = DateTimeFormat
			.forPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'");

	public static void getCompressedJS(String key) {
		key = FileIO.unescape(key);
		CompressedFile compressedFile = new ScriptCompressedFileManager().getCompressedFile(key);
		renderCompressedFile(compressedFile, "JavaScript");
	}

	public static void getCompressedFromFileName(String fileName) {
		if (fileName == null || fileName.isEmpty()) {
			notFound();
		}

		if (fileName.endsWith(".js")) {
			CompressedFile compressedFile = new ScriptCompressedFileManager().getCompressedFileFromName(fileName, "js");
			renderCompressedFile(compressedFile, "JavaScript");
		} else if (fileName.endsWith(".css")) {
			CompressedFile compressedFile = new StyleCompressedFileManager().getCompressedFileFromName(fileName, "css");
			renderCompressedFile(compressedFile, "CSS");
		} else {
			notFound();
		}
	}

	public static void getThemeCss(String ctx, String timestamp) {
		String fileName = "themes/theme-" + ctx + "-" + timestamp + ".less";
		VirtualFile file = VirtualFile.fromRelativePath("public/stylesheets/" + fileName);

		if(!file.exists()) {
			ContextCss.generateThemeFile(file, ctx);
		}
		CompressedFile compressedFile = new StyleCompressedFileManager().getCompressedFileFromName(fileName, "css");
		renderCompressedFile(compressedFile, "CSS");
	}

	public static void getCompressedJSFromConfig(String key) {
		if (key == null || key.isEmpty()) {
			key = "js.conf";
		}
		CompressedFile compressedFile = new ScriptCompressedFileManager().getCompressedFileFromConfig(key, "js");
		renderCompressedFile(compressedFile, "JavaScript");
	}

	public static void getCompressedCSS(String key) {
		key = FileIO.unescape(key);
		CompressedFile compressedFile = new StyleCompressedFileManager().getCompressedFile(key);
		renderCompressedFile(compressedFile, "CSS");
	}

	public static void getCompressedCSSFromConfig(String key) {
		if (key == null || key.isEmpty()) {
			key = "css.conf";
		}
		CompressedFile compressedFile = new StyleCompressedFileManager().getCompressedFileFromConfig(key, "css");
		renderCompressedFile(compressedFile, "CSS");
	}

	private static void renderCompressedFile(CompressedFile compressedFile, String type) {
		flash.keep();

		if (compressedFile == null) {
			renderBadResponse(type);
		}

		InputStream inputStream = compressedFile.inputStream();

		// This seems to be buggy, so instead of passing the file length we
		// reset the input stream and allow play to manually copy the bytes from
		// the input stream to the response
		// renderBinary(inputStream, compressedFile.name(),
		// compressedFile.length());

		try {
			if (inputStream.markSupported()) {
				inputStream.reset();
			}
		} catch (IOException e) {
			throw new UnexpectedException(e);
		}

		// If the caching strategy is always, the timestamp is not part of the
		// key. If we let the browser cache, then the browser will keep holding
		// old copies, even after changing the files at the server and
		// restarting the server, since the key will stay the same.
		// If the caching strategy is never, we also don't want to cache at the
		// browser, for obvious reasons.
		// If the caching strategy is Change, then the modified timestamp is a
		// part of the key, so if the file changes, the key in the html file
		// will be modified, and the browser will request a new version. Each
		// version can therefore be cached indefinitely.
		if (PluginConfig.cache.equals(CachingStrategy.Change)) {
			// Cache for a year
			response.setHeader("Cache-Control", "max-age=" + 31536000);
			response.setHeader("Expires", httpDateTimeFormatter.print(new DateTime().plusYears(1)));
			if (!PluginConfig.p3pHeader.isEmpty()) {
				response.setHeader("P3P", PluginConfig.p3pHeader);
			}
		}

		renderBinary(inputStream, compressedFile.name());
	}

	public static void clearJSCache() {
		if (!PluginConfig.cacheClearEnabled) {
			forbidden();
		}

		int count = ScriptRequestHandler.clearCache();
		renderText("Cleared " + count + " JS files from cache");
	}

	public static void clearCSSCache() {
		if (!PluginConfig.cacheClearEnabled) {
			forbidden();
		}

		int count = StyleRequestHandler.clearCache();
		renderText("Cleared " + count + " CSS files from cache");
	}

	private static void renderBadResponse(String fileType) {
		String response = "/*\n";
		response += "The compressed " + fileType + " file could not be generated.\n";
		response += "This can occur in two situations:\n";
		response += "1. The time between when the page was rendered by the ";
		response += "server and when the browser requested the compressed ";
		response += "file was greater than the timeout. (The timeout is ";
		response += "currently configured to be ";
		response += PluginConfig.compressionKeyStorageTime + ")\n";
		response += "2. There was an exception thrown while rendering the ";
		response += "page.\n";
		response += "*/";
		renderBinaryResponse(response);
	}

	private static void renderBinaryResponse(String response) {
		try {
			renderBinary(new ByteArrayInputStream(response.getBytes("UTF-8")));
		} catch (UnsupportedEncodingException e) {
			throw new UnexpectedException(e);
		}
	}

	@Finally
	public static void compress() throws IOException {
		String text = response.out.toString();

		final ByteArrayOutputStream gzip = gzip(text);
		response.setHeader("Content-Encoding", "gzip");
		response.setHeader("Content-Length", gzip.size() + "");
		response.out = gzip;
	}

	private static ByteArrayOutputStream gzip(final String input)
			throws IOException {
		final InputStream inputStream = new ByteArrayInputStream(input.getBytes());
		final ByteArrayOutputStream stringOutputStream = new ByteArrayOutputStream((int) (input.length() * 0.75));
		final OutputStream gzipOutputStream = new GZIPOutputStream(stringOutputStream);

		final byte[] buf = new byte[5000];
		int len;
		while ((len = inputStream.read(buf)) > 0) {
			gzipOutputStream.write(buf, 0, len);
		}

		inputStream.close();
		gzipOutputStream.close();

		return stringOutputStream;
	}
}