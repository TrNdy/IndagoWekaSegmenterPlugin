/**
 *
 */
package com.indago.tr2d.ui.view;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import com.indago.tr2d.ui.model.Tr2dWekaSegmentationModel;
import com.indago.tr2d.ui.util.JDoubleListTextPane;
import com.indago.tr2d.ui.util.UniversalFileChooser;

import bdv.util.Bdv;
import bdv.util.BdvHandlePanel;
import indago.ui.progress.DialogProgress;
import net.miginfocom.swing.MigLayout;
import weka.gui.ExtensionFileFilter;

/**
 * @author jug
 */
public class Tr2dWekaSegmentationPanel extends JPanel implements ActionListener, ListSelectionListener {

	private static final long serialVersionUID = 9192569315077150275L;

	private final Tr2dWekaSegmentationModel model;

	private final JList< String > listClassifiers;
	private final JButton bAdd = new JButton( "+" );
	private final JButton bRemove = new JButton( "-" );

	private JLabel lblThresholds;
	private JDoubleListTextPane txtThresholds;

	private JButton bStartSegmentation;

	private DialogProgress trackingProgressDialog = null;

	public Tr2dWekaSegmentationPanel( final Tr2dWekaSegmentationModel model ) {
		super( new BorderLayout() );
		this.model = model;
		listClassifiers = new JList<>( model.getListModel() );
		buildGui();
	}

	/**
	 * Builds the GUI of this panel.
	 */
	private void buildGui() {

		final MigLayout layout = new MigLayout( "fill", "[grow]", "" );
		final JPanel controls = new JPanel( layout );

		final JPanel list = new JPanel( new BorderLayout() );
		listClassifiers.addListSelectionListener( this );
		list.add( listClassifiers, BorderLayout.CENTER );
		list.setBorder( BorderFactory.createTitledBorder( "Fiji Trainable Weka Segmentation models" ) );
		JPanel helper = new JPanel( new FlowLayout( FlowLayout.RIGHT ) );
		bAdd.addActionListener( this );
		bRemove.addActionListener( this );
		helper.add( bAdd );
		helper.add( bRemove );
		list.add( helper, BorderLayout.SOUTH );
		controls.add( list, "h 100%, grow, wrap" );

		helper = new JPanel( new BorderLayout() );
		lblThresholds = new JLabel( "thresholds: " );
		txtThresholds = new JDoubleListTextPane();
		txtThresholds.setEnabled( false );
		helper.add( lblThresholds, BorderLayout.WEST );
		helper.add( txtThresholds, BorderLayout.CENTER );
		controls.add( helper, "growx, wrap" );

		bStartSegmentation = new JButton( "start selected classifiers" );
		bStartSegmentation.addActionListener( this );
		controls.add( bStartSegmentation, "growx, gapy 5 0, wrap" );

		final BdvHandlePanel bdv = new BdvHandlePanel( ( Frame ) this.getTopLevelAncestor(), Bdv
				.options()
				.is2D() );
		model.bdvSetHandlePanel( bdv );

		final JSplitPane splitPane = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT, controls, model.bdvGetHandlePanel().getViewerPanel() );
		splitPane.setResizeWeight( 0.1 ); // 1.0 == extra space given to left component alone!
		this.add( splitPane, BorderLayout.CENTER );
	}

	/**
	 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
	 */
	@Override
	public void actionPerformed( final ActionEvent e ) {
		if ( e.getSource().equals( bAdd ) ) {
			actionAddClassifierFile();
		} else if ( e.getSource().equals( bRemove ) ) {
			actionRemoveClassifierData();
		} else if ( e.getSource().equals( bStartSegmentation ) ) {
			if ( trackingProgressDialog == null ) {
				trackingProgressDialog = new DialogProgress( this, "Starting segmentation...", 10 );
				model.addProgressListener( trackingProgressDialog );
			} else {
				model.fireNextProgressPhaseEvent( "Starting segmentation...", 10 );
			}

			new Thread( () -> {
				actionStartSegmentaion();
			} ).start();
		}
	}

	/**
	 * Opens FileChooser to select a classifier later used for Weka
	 * segmentation.
	 */
	private void actionAddClassifierFile() {
		final ExtensionFileFilter eff =
				new ExtensionFileFilter( new String[] { "model", "MODEL" }, "weka-model-file" );
		final File file = UniversalFileChooser.showLoadFileChooser(
				this,
				"",
				"Classifier to be loaded...",
				eff );
		if ( file != null ) {
			model.importClassifier( file );
			listClassifiers.setSelectedIndex( listClassifiers.getModel().getSize() - 1 );
		}
	}

	/**
	 *
	 */
	private void actionRemoveClassifierData() {
		if ( listClassifiers.getSelectedIndices().length > 0 ) {
			int removedSoFar = 0;
			for ( final int idx : listClassifiers.getSelectedIndices() ) {
				model.removeClassifierAndData( idx - removedSoFar );
				removedSoFar++;
			}
		}
		model.saveStateToFile();
		if ( listClassifiers.getModel().getSize() > 0 ) {
			listClassifiers.setSelectedIndex( 0 );
		} else {
			listClassifiers.clearSelection();
			model.bdvRemoveAll();
		}
	}

	/**
	 * Start segmentation procedure.
	 */
	private void actionStartSegmentaion() {

		model.bdvRemoveAll();

		// START SEGMENTATION
		if ( listClassifiers.getSelectedIndex() != -1 ) { // update latest edits of text field that might have occured
			model.setListThresholds( listClassifiers.getSelectedIndex(), txtThresholds.getList() );
		}
		model.segmentSelected( listClassifiers.getSelectedIndices() );

		updateViewGivenSelection();

		model.fireProgressCompletedEvent();
	}

	private void updateViewGivenSelection() {
		if ( listClassifiers.getSelectedIndices().length > 0 ) {
			for ( final int idx : listClassifiers.getSelectedIndices() ) {
				if ( listClassifiers.getSelectedIndices().length > 1 ) {
					txtThresholds.setEnabled( false );
				} else {
					txtThresholds.setList( model.getListThresholds( idx ) );
					txtThresholds.setEnabled( true );
				}
				if ( model.getSumImages().size() > idx && model.getSumImages().get( idx ) != null ) {
					model.bdvAdd(
							model.getSumImages().get( idx ),
							listClassifiers.getModel().getElementAt( idx ) );
				}
			}
		} else {
			model.bdvRemoveAll();
		}
	}

	/**
	 * @see javax.swing.event.ListSelectionListener#valueChanged(javax.swing.event.ListSelectionEvent)
	 */
	@Override
	public void valueChanged( final ListSelectionEvent e ) {
		if ( e.getValueIsAdjusting() == false ) {

			model.bdvRemoveAll();
			updateViewGivenSelection();
		}
	}
}
