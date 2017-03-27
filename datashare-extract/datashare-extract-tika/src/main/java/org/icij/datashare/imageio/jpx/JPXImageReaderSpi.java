package org.icij.datashare.imageio.jpx;

import com.github.jaiimageio.impl.common.PackageUtil;
import com.github.jaiimageio.jpeg2000.impl.J2KImageReader;
import com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi;

import javax.imageio.IIOException;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ServiceRegistry;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * This class shims support for JPX format images until a fix for
 * <a href="https://github.com/jai-imageio/jai-imageio-jpeg2000/issues/8">jai-imageio-jpeg2000 issue #8</a>
 * is released.
 *
 * It does this by wrapping the {@link J2KImageReaderSpi} provided by that package.
 */
public class JPXImageReaderSpi extends ImageReaderSpi {

	private static final String [] writerSpiNames = {"org.icij.datashare.imageio.jpx.JPXImageWriterSpi"};
	private static final String[] formatNames = {"jpx"};
	private static final String[] extensions = {"jpx"};
	private static final String[] mimeTypes = {"image/jpx", "image/jpeg2000", "image/jp2"};
	private static final Class[] inputTypes = { ImageInputStream.class };

	private boolean registered = false;
	private ImageReaderSpi instance = new J2KImageReaderSpi();

	public JPXImageReaderSpi() {
		super(PackageUtil.getVendor(), PackageUtil.getVersion(),
				formatNames,
				extensions,
				mimeTypes,
				"com.github.jaiimageio.jpeg2000.impl.J2KImageReader",
				inputTypes,
				writerSpiNames,
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
	public boolean canDecodeInput(final Object source) throws IOException {
		return instance.canDecodeInput(source);
	}

	@Override
	public ImageReader createReaderInstance(final Object extension) throws IIOException {
		return new J2KImageReader(this);
	}

	@Override
	public String getDescription(final Locale locale) {
		return PackageUtil.getSpecificationTitle() + " JPEG 2000 (Extended) Image Reader";
	}
}
