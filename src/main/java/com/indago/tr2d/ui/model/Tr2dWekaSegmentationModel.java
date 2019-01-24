
/**
 *
 */
package com.indago.tr2d.ui.model;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
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
import com.indago.tr2d.plugins.seg.IndagoWekaSegmentationPlugin;
import com.indago.ui.bdv.BdvOwner;
import com.indago.util.converter.IntTypeThresholdConverter;
import com.jgoodies.common.collect.LinkedListModel;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;

import bdv.util.BdvHandlePanel;
import bdv.util.BdvSource;
import ij.IJ;
import indago.ui.progress.DialogProgress;
import indago.ui.progress.ProgressListener;
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

	private ProjectFolder projectFolder;
	private RandomAccessibleInterval< DoubleType > rawData;

	private final Vector< ProjectFile > vecClassifierFiles = new Vector< ProjectFile >();
	private final Vector< List< Double > > vecThresholds = new Vector< List< Double > >();

	private final LinkedListModel< String > linkedListModel = new LinkedListModel<>();

	private final Map< ProjectFile, RandomAccessibleInterval< DoubleType > > mapClassification = new HashMap<>();
	private final Map< ProjectFile, RandomAccessibleInterval< IntType > > mapSegmentHypotheses = new HashMap<>();

	private BdvHandlePanel bdvHandlePanel;
	private final List< BdvSource > bdvSources = new ArrayList< >();

	private final List< ProgressListener > progressListeners = new ArrayList<>();

	/**
	 * @param parentFolder
	 *
	 */
	public Tr2dWekaSegmentationModel( final ProjectFolder parentFolder, final RandomAccessibleInterval< DoubleType > rawData ) {
		try {
			this.projectFolder = parentFolder.addFolder( "weka" );
		} catch ( final IOException e ) {
			this.projectFolder = null;
			IndagoWekaSegmentationPlugin.log.error( "Subfolder for weka segmentation hypotheses could not be created." );
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
								IndagoWekaSegmentationPlugin.log.error( "savedState.cvs cannot correctly be parsed!", nfe );
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
			final File file = new File( projectFolder.getFolder(), FILENAME_PREFIX_CLASSIFICATION_IMGS + i + ".tif" );
			if ( file.canRead() ) {
				mapClassification.put(
						pfClassifier,
						DoubleTypeImgLoader.loadTiff( file ) );
				System.out.println( "loaded " + pfClassifier );
				final RandomAccessibleInterval< IntType > sumimg =
						IntTypeImgLoader.loadTiffEnsureType( new File( projectFolder.getFolder(), FILENAME_PREFIX_SUM_IMGS + i + ".tif" ) );
				mapSegmentHypotheses.put( pfClassifier, sumimg );
			} else {
				IndagoWekaSegmentationPlugin.log.warn(
						"No segmentation results found for classifier '" + pfClassifier
								.getFilename() + "'. Start segmentation for it if you want to use its output for tracking." );
			}
			i++;
		}
	}

	/**
	 *
	 */
	public void saveStateToFile() {
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
	public void segmentSelected( final int[] indices ) {
		for ( final int idx : indices ) {
			final ProjectFile pfClassifier = vecClassifierFiles.get( idx );

			IndagoWekaSegmentationPlugin.log
					.trace( String.format( "Classifier %d of %d -- %s", idx, vecClassifierFiles.size(), pfClassifier.getFilename() ) );

			if ( !pfClassifier.canRead() )
				IndagoWekaSegmentationPlugin.log.error( String.format( "Given classifier file cannot be read (%s)", pfClassifier.getAbsolutePath() ) );
			activateClassifier( pfClassifier );

			// classify frames (if needed)
			RandomAccessibleInterval< DoubleType > classification = mapClassification.get( pfClassifier );
			if ( classification == null ) {
				System.out.println( "need to segment for " + pfClassifier );
				classification =
						SegmentationMagic.returnClassification( rawData, progressListeners );
				mapClassification.put( pfClassifier, classification );
				IJ.save(
						ImageJFunctions.wrap( classification, "classification image" ).duplicate(),
						new File( projectFolder.getFolder(), FILENAME_PREFIX_CLASSIFICATION_IMGS + idx + ".tif" ).getAbsolutePath() );
			}

			// collect thresholds into SumImage
			RandomAccessibleInterval< IntType > imgTemp;
			final Img< IntType > sumimg = DataMover.createEmptyArrayImgLike( classification, new IntType() );
			mapSegmentHypotheses.put( pfClassifier, sumimg );

			for ( final Double d : getListThresholds( idx ) ) {
				imgTemp = Converters.convert(
						classification,
						new IntTypeThresholdConverter( d ),
						new IntType() );
				DataMover.add( imgTemp, ( IterableInterval ) sumimg );
			}
			IJ.save(
					ImageJFunctions.wrap( sumimg, "sum image" ).duplicate(),
					new File( projectFolder.getFolder(), FILENAME_PREFIX_SUM_IMGS + idx + ".tif" ).getAbsolutePath() );
		}
	}

	/**
	 * Performs the segmentation procedure previously set up.
	 */
	public void segment() {
		final int[] indices = new int[ vecClassifierFiles.size() ];
		for ( int i = 0; i < indices.length; i++ ) {
			indices[ i ] = i;
		}
		segmentSelected( indices );
	}

	/**
	 * @return
	 */
	public List< RandomAccessibleInterval< DoubleType > > getClassifications() {
		final List< RandomAccessibleInterval< DoubleType > > ret = new ArrayList<>();
		for ( final ProjectFile pfClassifier : vecClassifierFiles ) {
			ret.add( mapClassification.get( pfClassifier ) );
		}
		return ret;
	}

	/**
	 * @return
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
			IndagoWekaSegmentationPlugin.log.error( "setListThresholds called with invalid index!" );
		}
		saveStateToFile();
	}

	/**
	 * @see com.indago.ui.bdv.BdvOwner#bdvSetHandlePanel(BdvHandlePanel)
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
		if ( idx == -1 || getSumImages().get( idx ) == null ) return null;
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
	 * @param f
	 */
	public void importClassifier( final File f ) {
		final ProjectFile pf = projectFolder.addFile( f.getName() );
		try {
			Files.copy( f.toPath(), pf.getFile().toPath() );
			addClassifier( pf );
		} catch ( final FileAlreadyExistsException faee ) {
			projectFolder.removeFile( pf );
			final String msg = "A classifier file with this name was already added to the segmenter!";
			IndagoWekaSegmentationPlugin.log.error( msg, faee );
			JOptionPane.showMessageDialog(
					null, //Tr2dContext.guiFrame,
					"A classifier file with this name was already added to the segmenter!\n" + faee.getMessage(),
					"Already added...",
					JOptionPane.ERROR_MESSAGE );
		} catch ( final IOException e ) {
			projectFolder.removeFile( pf );
			IndagoWekaSegmentationPlugin.log.error( "Classifier could not be moved to project folder!", e );
		}
	}

	/**
	 * @param idx
	 */
	public void removeClassifierAndData( final int idx ) {
		linkedListModel.remove( idx );
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
		final String[] listOfFiles = projectFolder.getFolder().list( new ExtensionFileFilter( "tif", "tif" ) );
		for ( final String filename : listOfFiles ) {
			final File f = new File( projectFolder.getFolder(), filename );
			f.delete();
		}
	}

	/**
	 *
	 */
	private void saveAllImageFiles() {
		IndagoWekaSegmentationPlugin.log.trace( "(Re-)saving image data..." );
		int i = 0;
		for ( final ProjectFile pfClassifier : vecClassifierFiles ) {
			i++;

			final RandomAccessibleInterval< DoubleType > classification = mapClassification.get( pfClassifier );
			IJ.save(
					ImageJFunctions.wrap( classification, "classification image" ).duplicate(),
					new File( projectFolder.getFolder(), FILENAME_PREFIX_CLASSIFICATION_IMGS + i + ".tif" ).getAbsolutePath() );

			final RandomAccessibleInterval< IntType > sumimg = mapSegmentHypotheses.get( pfClassifier );
			IJ.save(
					ImageJFunctions.wrap( sumimg, "sum image" ).duplicate(),
					new File( projectFolder.getFolder(), FILENAME_PREFIX_SUM_IMGS + i + ".tif" ).getAbsolutePath() );
		}
	}

	/**
	 * @param progressListener
	 */
	public void addProgressListener( final ProgressListener progressListener ) {
		this.progressListeners.add( progressListener );
	}

	/**
	 * @param maxProgress
	 */
	public void setTotalProgressSteps( final int maxProgress ) {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.setTotalProgressSteps( maxProgress );
		}
	}

	/**
	 *
	 */
	public void fireProgressEvent() {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.hasProgressed();
		}
	}

	/**
	 * @param newMessage
	 */
	public void fireProgressEvent( final String newMessage ) {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.hasProgressed( newMessage );
		}
	}

	/**
	 * @param newMessage
	 * @param maxProgress
	 */
	public void fireNextProgressPhaseEvent( final String newMessage, final int maxProgress ) {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.resetProgress( newMessage, maxProgress );
		}
	}

	public void fireProgressCompletedEvent() {
		for ( final ProgressListener progressListener : this.progressListeners ) {
			progressListener.hasCompleted();
		}
	}

	/**
	 * @param progress
	 */
	public void removeProgressListener( final DialogProgress progress ) {
		this.progressListeners.remove( progress );
	}

}
