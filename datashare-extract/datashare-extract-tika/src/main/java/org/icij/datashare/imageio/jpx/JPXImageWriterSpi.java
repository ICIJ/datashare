package org.icij.datashare.imageio.jpx;

import javax.imageio.IIOException;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageOutputStream;
import java.util.Locale;

import com.github.jaiimageio.impl.common.PackageUtil;
import com.github.jaiimageio.jpeg2000.impl.J2KImageWriter;
import com.github.jaiimageio.jpeg2000.impl.J2KImageWriterSpi;


/**
 * This class shims support for JPX format images until a fix for
 * <a href="https://github.com/jai-imageio/jai-imageio-jpeg2000/issues/8">jai-imageio-jpeg2000 issue #8</a>
 * is released.
 *
 * It does this by wrapping the {@link J2KImageWriterSpi} provided by that package.
 */
public class JPXImageWriterSpi extends ImageWriterSpi {

	private static final String[] readerSpiNames = {"org.icij.datashare.imageio.jpx.JPXImageReaderSpi"};
	private static final String[] formatNames = {"jpx"};
	private static final String[] extensions = {"jpx"};
	private static final String[] mimeTypes = {"image/jpx", "image/jpeg2000", "image/jp2"};
	private static final Class[] outputTypes = { ImageOutputStream.class };

	private boolean registered = false;
	private ImageWriterSpi instance = new J2KImageWriterSpi();

	public JPXImageWriterSpi() {
		super(PackageUtil.getVendor(),
				PackageUtil.getVersion(),
				formatNames,
				extensions,
				mimeTypes,
				"com.github.jaiimageio.jpeg2000.impl.J2KImageWriter",
				outputTypes,
				readerSpiNames,
				false,
				null, null,
				null, null,
				true,
				"com_sun_media_imageio_plugins_jpeg2000_image_1.0",
				"com.github.jaiimageio.jpeg2000.impl.J2KMetadataFormat",
				null, null);
	}

	@Override
	public void onRegistration(final ServiceRegistry registry, final Class category) {
		if (!registered) {
			registered = true;
		}
	}

	@Override
	public boolean canEncodeImage(final ImageTypeSpecifier type) {
		return instance.canEncodeImage(type);
	}

	@Override
	public ImageWriter createWriterInstance(Object extension) throws IIOException {
		return new J2KImageWriter(this);
	}

	@Override
	public String getDescription(final Locale locale) {
		return PackageUtil.getSpecificationTitle() + " JPEG 2000 (Extended) Image Writer";
	}
}
