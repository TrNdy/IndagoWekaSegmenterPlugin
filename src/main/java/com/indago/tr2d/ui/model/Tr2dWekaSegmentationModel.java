
/**
 *
 */
package com.indago.tr2d.ui.model;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.swing.JOptionPane;
import javax.swing.ListModel;

import com.indago.data.segmentation.SegmentationMagic;
import com.indago.io.DataMover;
import com.indago.io.DoubleTypeImgLoader;
import com.indago.io.IntTypeImgLoader;
import com.indago.io.ProjectFile;
import com.indago.io.ProjectFolder;
import com.indago.tr2d.Tr2dContext;
import com.indago.tr2d.plugins.seg.Tr2dWekaSegmentationPlugin;
import com.indago.ui.bdv.BdvOwner;
import com.indago.util.converter.IntTypeThresholdConverter;
import com.jgoodies.common.collect.LinkedListModel;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;

import bdv.util.BdvHandlePanel;
import bdv.util.BdvSource;
import ij.IJ;
import io.scif.img.ImgIOException;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.DoubleType;
import weka.gui.ExtensionFileFilter;

/**
 * @author jug
 */
public class Tr2dWekaSegmentationModel implements BdvOwner {

	private final String FILENAME_STATE = "savedState.csv";
	private final String FILENAME_PREFIX_SUM_IMGS = "sumImage";
	private final String FILENAME_PREFIX_CLASSIFICATION_IMGS = "classificationImage";

	private final Tr2dSegmentationCollectionModel model;
	private ProjectFolder projectFolder;

	private final Vector< ProjectFile > vecClassifierFiles = new Vector< ProjectFile >();
	private final Vector< List< Double > > vecThresholds = new Vector< List< Double > >();

	private final LinkedListModel< String > linkedListModel = new LinkedListModel<>();

	private final Map< ProjectFile, RandomAccessibleInterval< DoubleType > > imgsClassification = new HashMap<>();
	private final Map< ProjectFile, RandomAccessibleInterval< IntType > > mapSegmentHypotheses = new HashMap<>();

	private BdvHandlePanel bdvHandlePanel;
	private final List< BdvSource > bdvSources = new ArrayList< >();

	/**
	 * @param parentFolder
	 *
	 */
	public Tr2dWekaSegmentationModel( final Tr2dSegmentationCollectionModel tr2dSegmentationCollectionModel, final ProjectFolder parentFolder ) {
		this.model = tr2dSegmentationCollectionModel;

		try {
			this.projectFolder = parentFolder.addFolder( "weka" );
		} catch ( final IOException e ) {
			this.projectFolder = null;
			Tr2dWekaSegmentationPlugin.log.error( "Subfolder for weka segmentation hypotheses could not be created." );
			e.printStackTrace();
		}

		loadStateFromProjectFolder();
	}

	/**
	 *
	 */
	private void loadStateFromProjectFolder() {
		final CsvParser parser = new CsvParser( new CsvParserSettings() );

		final File guiState = projectFolder.addFile( FILENAME_STATE ).getFile();
		try {
			final List< String[] > rows = parser.parseAll( new FileReader( guiState ) );
			for ( final String[] strings : rows ) {
				if ( strings.length >= 2 ) { // there must be a classifier filename and at least one threshold value
					boolean first = true;
					final List< Double > values = new ArrayList<>();
					for ( final String string : strings ) {
						if ( string == null ) continue; // happens if line end with a ','
						if ( first ) { // classifier filename
							final ProjectFile classifier = projectFolder.addFile( string );
							vecClassifierFiles.add( classifier );
							linkedListModel.add( classifier.getFilename() );
							first = false;
						} else { // threshold value
							try {
								final double d = Double.parseDouble( string );
								values.add( d );
							} catch ( final NumberFormatException nfe ) {
								Tr2dWekaSegmentationPlugin.log.error( "savedState.cvs cannot correctly be parsed!", nfe );
							}
						}
					}
					if ( first == false ) { // unless crap happened, add the loaded list of threshold values
						vecThresholds.add( values );
					}
				}
			}
		} catch ( final FileNotFoundException e ) {
		}

		// Try to load classification and sum images corresponding to given classifiers
		int i = 0;
		for ( final ProjectFile pfClassifier : vecClassifierFiles ) {
			i++;
			try {
				final File file = new File( projectFolder.getFolder(), FILENAME_PREFIX_CLASSIFICATION_IMGS + i + ".tif" );
				if ( file.canRead() ) {
					imgsClassification.put(
							pfClassifier,
    						DoubleTypeImgLoader.loadTiff( file ) );
    				final RandomAccessibleInterval< IntType > sumimg =
    						IntTypeImgLoader.loadTiffEnsureType( new File( projectFolder.getFolder(), FILENAME_PREFIX_SUM_IMGS + i + ".tif" ) );
					mapSegmentHypotheses.put( pfClassifier, sumimg );
				}
			} catch ( final ImgIOException e ) {
				JOptionPane.showMessageDialog(
						Tr2dContext.guiFrame,
						"Weka Segmentation Results could not be loaded from project folder.\n> " + e.getMessage(),
						"Problem loading from project...",
						JOptionPane.ERROR_MESSAGE );
				e.printStackTrace();
			}
		}
	}

	/**
	 *
	 */
	private void saveStateToFile() {
		try {
			final FileWriter writer = new FileWriter( new File( projectFolder.getFolder(), FILENAME_STATE ) );

			for ( int i = 0; i < vecClassifierFiles.size(); i++ ) {
				writer.append( vecClassifierFiles.get( i ).getFilename() );
				writer.append( ", " );
				for ( final Double value : vecThresholds.get( i ) ) {
					writer.append( value.toString() );
					writer.append( ", " );
				}
				writer.append( "\n" );
			}
			writer.flush();
			writer.close();
		} catch ( final IOException e ) {
			e.printStackTrace();
		}
	}


	/**
	 * Loads the given classifier file.
	 *
	 * @param classifierFile
	 */
	private void activateClassifier( final ProjectFile classifierFile ) {
		SegmentationMagic
				.setClassifier( classifierFile.getParent().getAbsolutePath() + "/", classifierFile.getFilename() );
	}

	/**
	 * @return
	 */
	public Vector< ProjectFile > getClassifiers() {
		return vecClassifierFiles;
	}

	/**
	 * Adds a classifier that was moved to the project folder structure.
	 * This function also adds an entry to the list of thresholds to be used.
	 *
	 * @param pfClassifier
	 */
	private void addClassifier( final ProjectFile pfClassifier ) {
		vecClassifierFiles.add( pfClassifier );
		final List< Double > defaultThresholdList = new ArrayList< Double >();
		defaultThresholdList.add( 0.5 );
		vecThresholds.add( defaultThresholdList );
		linkedListModel.add( pfClassifier.getFilename() );
		saveStateToFile();
	}

	/**
	 * Performs the segmentation procedure previously set up.
	 */
	public void segment() {
		imgsClassification.clear();
		mapSegmentHypotheses.clear();
		for ( final BdvSource bdvSource : bdvSources ) {
			bdvSource.removeFromBdv();
		}
		bdvSources.clear();

		int i = 0;
		for ( final ProjectFile pfClassifier : vecClassifierFiles ) {
			i++;
			Tr2dWekaSegmentationPlugin.log
					.trace( String.format( "Classifier %d of %d -- %s", i, vecClassifierFiles.size(), pfClassifier.getFilename() ) );

			if ( !pfClassifier.canRead() )
				Tr2dWekaSegmentationPlugin.log.error( String.format( "Given classifier file cannot be read (%s)", pfClassifier.getAbsolutePath() ) );
			activateClassifier( pfClassifier );

    		// classify frames
			final RandomAccessibleInterval< DoubleType > classification =
					SegmentationMagic.returnClassification( getModel().getModel().getRawData() );
			imgsClassification.put( pfClassifier, classification );
			IJ.save( ImageJFunctions.wrap( classification, "classification image" ).duplicate(),
					 new File( projectFolder.getFolder(), FILENAME_PREFIX_CLASSIFICATION_IMGS + i + ".tif" ).getAbsolutePath() );

			// collect thresholds into SumImage
			RandomAccessibleInterval< IntType > imgTemp;
			final Img< IntType > sumimg = DataMover.createEmptyArrayImgLike( classification, new IntType() );
			mapSegmentHypotheses.put( pfClassifier, sumimg );

			for ( final Double d : getListThresholds( i - 1 ) ) {
    			imgTemp = Converters.convert(
						classification,
    					new IntTypeThresholdConverter( d ),
						new IntType() );
				DataMover.add( imgTemp, ( IterableInterval ) sumimg );
    		}
			IJ.save( ImageJFunctions.wrap( sumimg, "sum image" ).duplicate(),
					 new File( projectFolder.getFolder(), FILENAME_PREFIX_SUM_IMGS + i + ".tif" ).getAbsolutePath() );
		}
	}

	/**
	 * @return
	 * @throws IllegalAccessException
	 */
	public List< RandomAccessibleInterval< DoubleType > > getClassifications() {
		final List< RandomAccessibleInterval< DoubleType > > ret = new ArrayList<>();
		for ( final ProjectFile pfClassifier : vecClassifierFiles ) {
			ret.add( imgsClassification.get( pfClassifier ) );
		}
		return ret;
	}

	/**
	 * @return
	 * @throws IllegalAccessException
	 */
	public List< RandomAccessibleInterval< IntType > > getSumImages() {
		final List< RandomAccessibleInterval< IntType > > ret = new ArrayList<>();
		for ( final ProjectFile pfClassifier : vecClassifierFiles ) {
			ret.add( mapSegmentHypotheses.get( pfClassifier ) );
		}
		return ret;
	}

	/**
	 * @return the listThresholds
	 */
	public List< Double > getListThresholds( final int index ) {
		return vecThresholds.get( index );
	}

	/**
	 *
	 * @param list
	 */
	public void setListThresholds( final int index, final List< Double > list ) {
		if ( index >= 0 && index < vecThresholds.size() ) {
			vecThresholds.removeElementAt( index );
			vecThresholds.add( index, list );
		} else {
			Tr2dWekaSegmentationPlugin.log.error( "setListThresholds called with invalid index!" );
		}
		saveStateToFile();
	}

	/**
	 * @return the model
	 */
	public Tr2dSegmentationCollectionModel getModel() {
		return model;
	}

	/**
	 * @see com.indago.ui.bdv.BdvOwner#setBdvHandlePanel()
	 */
	@Override
	public void bdvSetHandlePanel( final BdvHandlePanel bdvHandlePanel ) {
		this.bdvHandlePanel = bdvHandlePanel;
	}

	/**
	 * @see com.indago.ui.bdv.BdvOwner#bdvGetHandlePanel()
	 */
	@Override
	public BdvHandlePanel bdvGetHandlePanel() {
		return bdvHandlePanel;
	}

	/**
	 * @see com.indago.ui.bdv.BdvOwner#bdvGetSources()
	 */
	@Override
	public List< BdvSource > bdvGetSources() {
		return bdvSources;
	}

	/**
	 * @see com.indago.ui.bdv.BdvOwner#bdvGetSourceFor(net.imglib2.RandomAccessibleInterval)
	 */
	@Override
	public < T extends RealType< T > & NativeType< T > > BdvSource bdvGetSourceFor( final RandomAccessibleInterval< T > img ) {
		final int idx = getSumImages().indexOf( img );
		if ( idx == -1 ) return null;
		return bdvGetSources().get( idx );
	}

	/**
	 * @return
	 */
	public ProjectFolder getProjectFolder() {
		return this.projectFolder;
	}

	/**
	 * @return
	 */
	public ListModel< String > getListModel() {
		return linkedListModel;
	}

	/**
	 * @param file
	 */
	public void importClassifier( final File f ) {
		final ProjectFile pf = projectFolder.addFile( f.getName() );
		try {
			Files.copy( f.toPath(), pf.getFile().toPath() );
			addClassifier( pf );
		} catch ( final IOException e ) {
			projectFolder.removeFile( pf );
			Tr2dWekaSegmentationPlugin.log.error( "Classifier could not be moved to project folder!", e );
		}
	}

	/**
	 * @param i
	 */
	public void removeClassifierAndData( final int idx ) {
		final ProjectFile toDelete = vecClassifierFiles.remove( idx );
		toDelete.getFile().delete();
		vecThresholds.remove( idx );
		deleteAllSavedImageFiles();
		saveAllImageFiles();
	}

	/**
	 *
	 */
	private void deleteAllSavedImageFiles() {
		final String[] listOfFiles = projectFolder.getFolder().list( new ExtensionFileFilter( ".tif", "TIFF Files" ) );
		for ( final String filename : listOfFiles ) {
			final File f = new File( filename );
			f.delete();
		}
	}

	/**
	 *
	 */
	private void saveAllImageFiles() {
		// TODO Auto-generated method stub

	}
}
