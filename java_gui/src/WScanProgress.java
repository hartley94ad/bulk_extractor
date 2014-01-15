import java.awt.*;
import javax.swing.*;
import java.awt.event.*;
import java.lang.Runtime;
import java.lang.Process;
import java.util.Observable;
import java.util.Observer;
import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The dialog window for showing progress on a started bulk_extractor scan
 * process.
 * Multiple windows and scans may be active.  This window is not modal.
 */

public class WScanProgress extends JDialog {
  private static final long serialVersionUID = 1;

  private final FileComponent imageFileLabel = new FileComponent();
  private final FileComponent featureDirectoryLabel = new FileComponent();
  private final JTextField commandField = new JTextField();

  private final JProgressBar progressBar = new JProgressBar();
  private final JLabel statusL = new JLabel();
  private final JTextArea outputArea = new JTextArea();
  private final JButton cancelB = new JButton("Cancel");
  private final JButton closeB = new JButton("Close");

  private final ScanSettings scanSettings;
  private final Process process;
  private ThreadStdout threadStdout;
  private ThreadStderr threadStderr;

  // runnable class for stdout
  private class RunnableShowStdout implements Runnable {
    private final String input;
    private RunnableShowStdout(String input) {
      this.input = input;
    }
    public void run() {
      // set progress % in progress bar
      // check input for progress identifier: "(.*%)"
      int leftParenIndex = input.indexOf("(");
      int rightParenIndex = input.indexOf("%)");
      if (leftParenIndex > 0 && rightParenIndex > leftParenIndex) {
        // this qualifies as a progress line

        // put progress line in the status label
        statusL.setText(input);

        // set % in progress bar
        String progress = input.substring(leftParenIndex + 1, rightParenIndex);
        try {
          float progressFloat = Float.parseFloat(progress);
          progressBar.setValue((int)progressFloat);
          progressBar.setString(Float.toString(progressFloat) + "%");
        } catch (NumberFormatException e) {
          WLog.log("WScanProgress.run: unexpected progress value '"
                   + progress + "' in stdout: " + input);
        }

      } else {
        // forward everything else to outputArea
        outputArea.append(input);
        outputArea.append("\n");
      }
    }
  }

  // runnable class for stderr
  private class RunnableShowStderr implements Runnable {
    private final String input;
    public RunnableShowStderr(String input) {
      this.input = input;
    }

    public void run() {
      // forward all stderr to outputArea and to log
      WLog.log("bulk_extractor scan error: '" + input + "'");
      outputArea.append(input);
      outputArea.append("\n");
    }
  }

  // runnable class for showing "done" information
  private class RunnableSetDoneState implements Runnable {
    public void run() {
      // enable "close" and disable "cancel"
      closeB.setEnabled(true);
      cancelB.setEnabled(false);

      // get the process' exit value
      int exitValue = process.exitValue();

      // respond to termination based on the process' exit value
      if (exitValue == 0) {
        // successful run
        statusL.setText("bulk_extractor scan completed.  Report "
                        + new File(scanSettings.outdir).getName()
                        + " is ready.");
        progressBar.setValue(100);
        progressBar.setString("Done");

        // add the report that has been generated by this run
        BEViewer.reportsModel.addReport(new File(scanSettings.outdir),
                                        new File(scanSettings.inputImage));

      } else if (exitValue == 143) {
        // canceled run
        statusL.setText("The bulk_extractor scan was canceled");
        WLog.log("bulk_extractor scan canceled");
        progressBar.setString("Canceled");
        WError.showError("bulk_extractor Scanner canceled."
                         + "\n" + scanSettings.getCommandString(),
                         "bulk_extractor scan canceled", null);

      } else {
        // failed run
        statusL.setText("Error: bulk_extractor terminated with exit value "
                        + exitValue + ".  Please check command syntax.");
        WLog.log("bulk_extractor error exit value: " + exitValue);
        progressBar.setString("Error");
        WError.showError("bulk_extractor Scanner terminated with exit value "
                         + exitValue + ".  Please check command syntax:"
                         + "\n" + scanSettings.getCommandString(),
                         "bulk_extractor scan terminated", null);
      }
    }
  }

  // thread to forward stdout to RunnableShowStdout
  private class ThreadStdout extends Thread {
    private final BufferedReader readFromStdout;

    ThreadStdout() {
      readFromStdout = new BufferedReader(new InputStreamReader(
                                              process.getInputStream()));
    }

    public void run() {
      while (true) {
        try {
          // block wait until EOF
          String input = readFromStdout.readLine();
          if (input == null) {
            break;
          } else {
            SwingUtilities.invokeLater(new RunnableShowStdout(input));
          }
        } catch (IOException e) {
          WLog.log("ScanSettingsConsumer.ThreadStdout.run aborting.");
          break;
        }
      }
    }
  }

  // thread to forward stderr to RunnableShowStderr
  private class ThreadStderr extends Thread {
    private final BufferedReader readFromStderr;

    ThreadStderr() {
      readFromStderr = new BufferedReader(new InputStreamReader(
                                              process.getErrorStream()));
    }

    public void run() {
      while (true) {
        try {
          // block wait until EOF
          String input = readFromStderr.readLine();
          if (input == null) {
            break;
          } else {
            SwingUtilities.invokeLater(new RunnableShowStderr(input));
          }
        } catch (IOException e) {
          WLog.log("ScanSettingsConsumer.ThreadStderr.run aborting.");
          break;
        }
      }
    }
  }

  // RunnableWScanProgress class for creating WScanProgress
  // from the Swing thread
  private static class RunnableWScanProgress implements Runnable {
    private final ScanSettings scanSettings;
    private final Process process;
    RunnableWScanProgress(ScanSettings scanSettings, Process process) {
      this.scanSettings = scanSettings;
      this.process = process;
    }
    public void run() {
      new WScanProgress(scanSettings, process);
    }
  }

  // thread for waiting for the stdout and stderr streams
  // and the bulk_extractor process to complete.
  // When done, run RunnableSetDoneState on Swing thread.
  private class ThreadDoneWaiter extends Thread {
    public void run() {
      // wait for the thread readers to finish
      try {
        threadStdout.join();
      } catch (InterruptedException ie1) {
        throw new RuntimeException("unexpected event");
      }
      try {
        threadStderr.join();
      } catch (InterruptedException ie2) {
        throw new RuntimeException("unexpected event");
      }

      // wait for the bulk_extractor scan process to finish
      // Note: this isn't really necessary since being finished is implied
      // when the Thread readers finish.
      // Note: the process terminates by itself or by process.destroy().
      try {
        process.waitFor();
      } catch (InterruptedException ie) {
        throw new RuntimeException("unexpected event");
      }

      // now set process done state
      SwingUtilities.invokeLater(new RunnableSetDoneState());
    }
  }


  /**
   * Threadsafe method for opening WScanProgress.
   */
  public static void openWindow(ScanSettings scanSettings, Process process) {
    SwingUtilities.invokeLater(new RunnableWScanProgress(scanSettings, process));
  }

  // call openWindow to run this privately on the Swing thread
  private WScanProgress(ScanSettings scanSettings, Process process) {
    this.scanSettings = scanSettings;
    this.process = process;

    setLocationRelativeTo(BEViewer.getBEWindow());
    setClosePolicy();
    buildInterface();
    getRootPane().setDefaultButton(cancelB);
    wireActions();
    setStartState();
    pack();
    setVisible(true);
  }

  // set window closure policy
  private void setClosePolicy() {
    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    addWindowListener(new WindowAdapter() {
      public void windowClosing(WindowEvent e) {
        doClose();
      }
    });
  }

  // set start fields and start stdout and stderr reader threads
  private void setStartState() {
    // put scanSettings values into UI components
    imageFileLabel.setFile(new File(scanSettings.inputImage));
    featureDirectoryLabel.setFile(new File(scanSettings.outdir));

    // show the scanSettings command string in the command field
    String commandString = scanSettings.getCommandString();
    commandField.setText(commandString);
    commandField.setToolTipText(commandString);
    commandField.setCaretPosition(0);

    // start stdout reader
    threadStdout = new ThreadStdout();
    threadStdout.start();

    // start stderr reader
    threadStderr = new ThreadStderr();
    threadStderr.start();

    // start thread for waiting for everything to finish
    new ThreadDoneWaiter().start();
  }

  private void buildInterface() {
    // set the title to include the image filename
    setTitle("bulk_extractor Scan");

    // use GridBagLayout with GridBagConstraints
    GridBagConstraints c;
    Container pane = getContentPane();
    pane.setLayout(new GridBagLayout());

    // (0,0) File container
    c = new GridBagConstraints();
    c.insets = new Insets(15, 5, 0, 5);
    c.gridx = 0;
    c.gridy = 0;
    c.fill = GridBagConstraints.HORIZONTAL;
    pane.add(getFileContainer(), c);

    // (0,1) Command field container
    c = new GridBagConstraints();
    c.insets = new Insets(15, 5, 0, 5);
    c.gridx = 0;
    c.gridy = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
//    c.anchor = GridBagConstraints.LINE_START;
    pane.add(getCommandContainer(), c);

    // (0,2) Progress container
    c = new GridBagConstraints();
    c.insets = new Insets(15, 5, 0, 5);
    c.gridx = 0;
    c.gridy = 2;
    c.anchor = GridBagConstraints.LINE_START;
    pane.add(getProgressContainer(), c);

    // (0,3) bulk_extractor output area container
    c = new GridBagConstraints();
    c.insets = new Insets(15, 5, 0, 5);
    c.gridx = 0;
    c.gridy = 3;
    c.weightx= 1;
    c.weighty= 1;
    c.fill = GridBagConstraints.BOTH;
    pane.add(getOutputContainer(), c);

    // (0,4) controls
    c = new GridBagConstraints();
    c.insets = new Insets(5, 5, 5, 5);
    c.gridx = 0;
    c.gridy = 4;

    // add the cancel button
    pane.add(buildControls(), c);
  }

  private Component buildControls() {
    GridBagConstraints c;
    Container container = new Container();
    container.setLayout(new GridBagLayout());

    // cancel
    c = new GridBagConstraints();
    c.insets = new Insets(5, 5, 5, 5);
    c.gridx = 0;
    c.gridy = 0;
    container.add(cancelB, c);

    // close
    closeB.setEnabled(false);
    c = new GridBagConstraints();
    c.insets = new Insets(5, 5, 5, 5);
    c.gridx = 1;
    c.gridy = 0;
//    c.anchor = GridBagConstraints.FIRST_LINE_START;
    container.add(closeB, c);

    return container;
  }

  private void wireActions() {
    // cancelB
    cancelB.addActionListener(new ActionListener() {
      public void actionPerformed (ActionEvent e) {
        doCancel();
      }
    });
    // closeB
    closeB.addActionListener(new ActionListener() {
      public void actionPerformed (ActionEvent e) {
        doClose();
      }
    });
  }

  // find out if the process is alive
  private static boolean isAlive(Process process) {
    // Unfortunately, the way to do this is unexpected:
    // poll exitValue, which throws IllegalThreadStateException when alive.
    try {
      int status = process.exitValue();
      // the process has terminated because this did not throw
      return false;
    } catch (IllegalThreadStateException e) {
      // the process is still alive
      return true;
    }
  }

  // cancel
  private void doCancel() {
    if (isAlive(process)) {
      process.destroy();
    } else {
      WLog.log("WScanProgress.doCancel: process already canceled");
    }
  }

  // close
  private void doClose() {
    if (isAlive(process)) {
      WError.showMessage("The bulk_extractor Scan window cannot be closed\n"
                  + "because bulk_extractor is running.", "bulk_extractor is running");
    } else {
      dispose();
    }
  }

  // File container
  private Container getFileContainer() {
    GridBagConstraints c;
    Container container = new Container();
    container.setLayout(new GridBagLayout());

    // (0,0) "Image File"
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 10);
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.LINE_START;
    container.add(new JLabel("Image File"), c);

    // (1,0) <image file>
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 0);
    c.gridx = 1;
    c.gridy = 0;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    container.add(imageFileLabel, c);

    // (0,1) "Feature Directory"
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 10);
    c.gridx = 0;
    c.gridy = 1;
    c.anchor = GridBagConstraints.LINE_START;
    container.add(new JLabel("Feature Directory"), c);

    // (1,1) <feature directory>
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 0);
    c.gridx = 1;
    c.gridy = 1;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    container.add(featureDirectoryLabel, c);

    return container;
  }

  // Command container
  private Container getCommandContainer() {
    GridBagConstraints c;
    Container container = new Container();
    container.setLayout(new GridBagLayout());

    // (0,0) "command"
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 0);
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.LINE_START;
    container.add(new JLabel("Command"), c);

    // (0,1) command text field
    commandField.setEditable(false);
    commandField.setMinimumSize(new Dimension(0, commandField.getPreferredSize().height));

    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 0);
    c.gridx = 0;
    c.gridy = 1;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;

    // add the command field
    container.add(commandField, c);

    return container;
  }

  // Progress container
  private Container getProgressContainer() {
    GridBagConstraints c;
    Container container = new Container();
    container.setLayout(new GridBagLayout());

    // (0,0) "progress"
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 10);
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.LINE_START;
    container.add(new JLabel("Progress"), c);

    // (1,0) progress bar
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 0);
    c.gridx = 1;
    c.gridy = 0;
    c.anchor = GridBagConstraints.LINE_START;
    progressBar.setPreferredSize(new Dimension(80, progressBar.getPreferredSize().height));
    progressBar.setMinimumSize(progressBar.getPreferredSize());
    progressBar.setStringPainted(true);
    progressBar.setMinimum(0);
    progressBar.setMaximum(100);
    progressBar.setValue(0);
    progressBar.setString("0%");
    container.add(progressBar, c);

    // (0,1) status text
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 0);
    c.gridx = 0;
    c.gridy = 1;
    c.weightx = 1;
    c.weighty = 1;
    c.gridwidth = 2;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.LINE_START;
    container.add(statusL, c);

    return container;
  }

  // bulk_extractor output container
  private Container getOutputContainer() {
    GridBagConstraints c;
    Container container = new Container();
    container.setLayout(new GridBagLayout());

    // (0,0) "bulk_extractor output"
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 0);
    c.gridx = 0;
    c.gridy = 0;
    c.anchor = GridBagConstraints.LINE_START;
    container.add(new JLabel("bulk_extractor output"), c);

    // (0,1) output scrollpane for containing output from bulk_extractor
    outputArea.setEditable(false);
    JScrollPane outputScrollPane = new JScrollPane(outputArea,
                       ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                       ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    outputScrollPane.setPreferredSize(new Dimension(600, 200));
    c = new GridBagConstraints();
    c.insets = new Insets(0, 0, 0, 0);
    c.gridx = 0;
    c.gridy = 1;
    c.weightx = 1;
    c.weighty = 1;
    c.fill = GridBagConstraints.BOTH;

    // add the output scrollpane
    container.add(outputScrollPane, c);

    return container;
  }
}

