/**
 *
 */
package com.indago.tr2d.plugins.seg;

import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

import org.scijava.log.Logger;
import org.scijava.plugin.Plugin;

import com.indago.IndagoLog;
import com.indago.io.ProjectFolder;
import com.indago.plugins.seg.IndagoSegmentationPlugin;
import com.indago.tr2d.ui.model.Tr2dWekaSegmentationModel;
import com.indago.tr2d.ui.view.Tr2dWekaSegmentationPanel;

import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.DoubleType;

/**
 * @author jug
 */
@Plugin( type = IndagoSegmentationPlugin.class, name = "Indago Weka Segmentation" )
public class IndagoWekaSegmentationPlugin implements IndagoSegmentationPlugin {

	JPanel panel = null;

	private ProjectFolder projectFolder;
	private Tr2dWekaSegmentationModel model;

	public static Logger log = IndagoLog.stderrLogger().subLogger(IndagoWekaSegmentationPlugin.class.getSimpleName());

	@Override
	public JPanel getInteractionPanel() {
		return panel;
	}

	@Override
	public List< RandomAccessibleInterval< IntType > > getOutputs() {
		final List< RandomAccessibleInterval< IntType > >  ret = new ArrayList<>();
		for ( final RandomAccessibleInterval< IntType > elem : model.getSumImages() ) {
			if ( elem != null ) { // in case some added classifiers have never been used to predict anything
				ret.add( elem );
			}
		}
		return ret;
	}

	@Override
	public void setProjectFolderAndData( final ProjectFolder projectFolder, final ImgPlus< DoubleType > rawData ) {
		this.projectFolder = projectFolder;
		this.model = new Tr2dWekaSegmentationModel( projectFolder, rawData );
		panel = new Tr2dWekaSegmentationPanel( this.model );
		log.info( "IndagoWekaSegmentationPlugin is set up." );
	}

	@Override
	public String getUiName() {
		return "weka segmentation";
	}

	@Override
	public void setLogger(final Logger logger) {
		log = logger;
	}
}
