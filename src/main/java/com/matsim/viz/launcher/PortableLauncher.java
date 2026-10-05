package com.matsim.viz.launcher;

import com.matsim.viz.config.AppDefaults;
import com.matsim.viz.parser.MatsimScenarioLoader;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;

/** Small Swing bootstrap. Each viewer runs in its own bundled JVM, allowing retries without restarting JavaFX. */
public final class PortableLauncher {
    private final JFrame window = new JFrame("MATSim Fast Visualization - Open simulation");
    private final JTextField config = new JTextField(), cache = new JTextField();
    private final Map<JTextField,JButton> browseButtons = new HashMap<>();
    private final Map<String,JTextField> inputs = new LinkedHashMap<>();
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"Simulation files - reuse or build cache", "Existing cache - no source files required", "Network only - view or edit XML (+ optional geometry CSV)"});
    private final JComboBox<Path> entries = new JComboBox<>();
    private final JLabel status = new JLabel("Choose a config to discover simulation files.");
    private final JButton run = new JButton("Run visualization"), scan = new JButton("Rescan config");
    private final JPanel form = new FormPanel();
    private final JTextArea startupLog = new JTextArea();
    private final JProgressBar progress = new JProgressBar();
    private final JPanel loadingPanel = new JPanel(new BorderLayout(8, 8));
    private JScrollPane formScroll;
    private final Path userDir;
    private final Properties base;
    private final javax.swing.Timer configTimer, cacheTimer;
    private long generation;
    private boolean loading;
    private volatile Process process;
    private int row;

    public static void main(String[] args) {
        var closed = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                var launcher = new PortableLauncher();
                launcher.window.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosed(WindowEvent event) { closed.countDown(); }
                });
                launcher.show();
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(null,ex.toString(),"Startup failed",JOptionPane.ERROR_MESSAGE);
                closed.countDown();
            }
        });
        // Keep Maven exec:java alive; otherwise it interrupts the launcher's worker threads.
        if (!SwingUtilities.isEventDispatchThread()) {
            try { closed.await(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }
    private PortableLauncher() throws Exception {
        userDir=Path.of(System.getProperty("matsim.viz.userDir", Path.of(System.getProperty("user.home"),AppDefaults.Launcher.USER_DIRECTORY).toString()));
        Files.createDirectories(userDir);
        Path packaged=Path.of(PortableLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().resolve(AppDefaults.Paths.APP_CONFIG);
        Path local=Path.of(AppDefaults.Paths.APP_CONFIG).toAbsolutePath();
        Path defaults=Files.isRegularFile(packaged)?packaged:local;
        base=Files.isRegularFile(defaults)?LaunchSettings.read(defaults):new Properties();
        Path last=userDir.resolve("launcher.properties");
        if(Files.isRegularFile(last))base.putAll(LaunchSettings.read(last));
        config.setText(base.getProperty("matsim.config.file",""));
        cache.setText(base.getProperty("cache.dir",userDir.resolve(AppDefaults.Paths.CACHE_DIR).toString()));
        addRow("Start from",mode,null);
        addRow("MATSim config",config,browse(config,false));
        addRow("Discover files",scan,null);
        String[][] fields={{"network","Network *"},{"geometry","Detailed geometry CSV"},{"events","Events *"},{"population","Population (optional)"},{"trips","Trips CSV"},{"persons","Persons CSV"},{"plans","Output plans"},{"schedule","Transit schedule"},{"vehicles","Transit vehicles"}};
        for(String[] field:fields){var text=new JTextField(base.getProperty("input."+field[0],""));inputs.put(field[0],text);addRow(field[1],text,browse(text,false));}
        addRow("Cache folder",cache,browse(cache,true));
        addRow("Existing cache",entries,null);
        entries.setRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean selected,boolean focus){return super.getListCellRendererComponent(l,value instanceof Path p?p.getFileName():value,index,selected,focus);}});
        JLabel help=new JLabel("<html>* Simulation mode requires config, network and events. Network-only mode requires just network XML.<br>Detailed geometry CSV is optional. Playback, recording and volumes require simulation data.</html>");
        JPanel bottom=new JPanel(new BorderLayout(8,8));bottom.add(help,BorderLayout.NORTH);bottom.add(status,BorderLayout.CENTER);bottom.add(run,BorderLayout.SOUTH);
        bottom.setBorder(BorderFactory.createEmptyBorder(10,12,12,12));
        formScroll = new JScrollPane(form);
        startupLog.setEditable(false);
        startupLog.setLineWrap(true);
        startupLog.setWrapStyleWord(true);
        loadingPanel.setBorder(BorderFactory.createEmptyBorder(12,12,0,12));
        loadingPanel.add(progress, BorderLayout.NORTH);
        loadingPanel.add(new JScrollPane(startupLog), BorderLayout.CENTER);
        window.add(formScroll,BorderLayout.CENTER);window.add(bottom,BorderLayout.SOUTH);
        Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setSize(Math.min(AppDefaults.Launcher.WIDTH_PIXELS,(int)(screen.width*AppDefaults.Launcher.SCREEN_FRACTION)),Math.min(AppDefaults.Launcher.HEIGHT_PIXELS,(int)(screen.height*AppDefaults.Launcher.SCREEN_FRACTION)));
        window.setLocationRelativeTo(null);window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){if(loading || (process!=null&&process.isAlive())){window.setState(Frame.ICONIFIED);}else window.dispose();}});
        configTimer=new javax.swing.Timer(AppDefaults.Launcher.SCAN_DELAY_MS,e->scan());configTimer.setRepeats(false);
        cacheTimer=new javax.swing.Timer(AppDefaults.Launcher.SCAN_DELAY_MS,e->refreshCaches());cacheTimer.setRepeats(false);
        changed(config,()->{generation++;if(mode.getSelectedIndex()==0){run.setEnabled(false);configTimer.restart();}});
        changed(cache,cacheTimer::restart);
        scan.addActionListener(e->scan());run.addActionListener(e->run());mode.addActionListener(e->updateMode());
        mode.setSelectedIndex(switch(base.getProperty("launch.mode",AppDefaults.Launcher.MODE)){case "cache" -> 1; case "network" -> 2; default -> 0;});
        refreshCaches();updateMode();
        if(inputs.values().stream().allMatch(t->t.getText().isBlank()) && !config.getText().isBlank())scan();
    }
    private void show(){window.setVisible(true);}
    private static final class FormPanel extends JPanel implements Scrollable {
        FormPanel() { super(new GridBagLayout()); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return getFontMetrics(getFont()).getHeight(); }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return visible.height; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
    private void addRow(String label,JComponent field,JComponent button){
        field.setMinimumSize(new Dimension(0, field.getPreferredSize().height));
        var c=new GridBagConstraints();c.gridy=row++;c.insets=new Insets(5,10,5,10);c.anchor=GridBagConstraints.WEST;c.gridx=0;form.add(new JLabel(label),c);
        c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;form.add(field,c);
        if(button!=null){c.gridx=2;c.weightx=0;form.add(button,c);}
    }
    private JButton browse(JTextField target,boolean directory){var b=new JButton("Browse...");b.addActionListener(e->{var chooser=new JFileChooser();chooser.setFileSelectionMode(directory?JFileChooser.DIRECTORIES_ONLY:JFileChooser.FILES_ONLY);if(!target.getText().isBlank())chooser.setSelectedFile(new java.io.File(target.getText()));if(chooser.showOpenDialog(window)==JFileChooser.APPROVE_OPTION)target.setText(chooser.getSelectedFile().getAbsolutePath());});browseButtons.put(target,b);return b;}
    private static void changed(JTextField field,Runnable action){field.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){action.run();}public void removeUpdate(DocumentEvent e){action.run();}public void changedUpdate(DocumentEvent e){action.run();}});}
    private void updateMode(){
        generation++;configTimer.stop();
        boolean files=mode.getSelectedIndex()==0, network=mode.getSelectedIndex()==2;
        enableField(config,files);scan.setEnabled(files);
        inputs.forEach((key,text)->enableField(text,files || key.equals("geometry") || (network && key.equals("network"))));
        enableField(cache,!network);entries.setEnabled(mode.getSelectedIndex()==1);run.setEnabled(!loading);
        status.setText(network ? "Choose a network XML and optionally a detailed geometry CSV."
                : files ? "Choose a config or review the simulation file paths." : "Choose a cache folder and an existing cache entry.");
    }
    private void enableField(JTextField field,boolean enabled){field.setEnabled(enabled);var browse=browseButtons.get(field);if(browse!=null)browse.setEnabled(enabled);}
    private void scan(){
        if(loading||mode.getSelectedIndex()!=0)return;
        long request=++generation;String path=config.getText().trim();
        inputs.forEach((key,text)->{if(!key.equals("geometry"))text.setText("");});run.setEnabled(false);status.setText("Scanning config...");
        new SwingWorker<MatsimScenarioLoader.InputDiscovery,Void>(){
            protected MatsimScenarioLoader.InputDiscovery doInBackground(){return new MatsimScenarioLoader().discoverInputs(Path.of(path));}
            protected void done(){if(request!=generation)return;try{var found=get();found.files().forEach((key,value)->inputs.get(key).setText(value.toString()));status.setText(found.warnings().isEmpty()?"Files discovered. Review paths, then Run.":"Some files were not found. Choose their paths below (network and events required).");status.setToolTipText(String.join("; ",found.warnings()));}catch(Exception ex){status.setText("Could not scan config: "+cause(ex).getMessage());}finally{run.setEnabled(true);}}
        }.execute();
    }
    private void refreshCaches(){
        String directory=cache.getText().trim();Object previous=entries.getSelectedItem();
        new SwingWorker<List<Path>,Void>(){protected List<Path> doInBackground()throws Exception{return LaunchSettings.caches(Path.of(directory));}
            protected void done(){if(!directory.equals(cache.getText().trim()))return;try{entries.removeAllItems();for(Path p:get())entries.addItem(p);String selected=previous==null?base.getProperty("launch.cache.file",""):previous.toString();for(int i=0;i<entries.getItemCount();i++)if(entries.getItemAt(i).toString().equals(selected))entries.setSelectedIndex(i);}catch(Exception ex){status.setText("Cannot list cache folder: "+cause(ex).getMessage());}}}.execute();
    }
    private Properties selection(){Properties p=new Properties();p.putAll(base);p.setProperty("matsim.config.file",config.getText().trim());p.setProperty("cache.dir",cache.getText().trim());p.setProperty("launch.mode",mode.getSelectedIndex()==1?"cache":mode.getSelectedIndex()==2?"network":"files");inputs.forEach((key,text)->p.setProperty("input."+key,text.getText().trim()));p.setProperty("launch.cache.file",entries.getSelectedItem()==null?"":entries.getSelectedItem().toString());return p;}
    private void run(){
        Properties settings=selection();loading=true;configTimer.stop();generation++;setEnabled(form,false);run.setEnabled(false);status.setText("Loading simulation... Startup details are written to the log.");
        startupLog.setText("");
        progress.setIndeterminate(true);
        window.remove(formScroll);window.add(loadingPanel,BorderLayout.CENTER);window.revalidate();window.repaint();
        new SwingWorker<Integer,String>(){
            Path log,session,javaArgs;
            protected Integer doInBackground()throws Exception{
                LaunchSettings.resolve(settings);
                LaunchSettings.write(userDir.resolve("launcher.properties"),settings);
                session=Files.createTempFile(userDir,"launch-",".properties");LaunchSettings.write(session,settings);
                log=Files.createTempFile(userDir,"startup-",".log");
                Path javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java");
                javaArgs=Files.createTempFile(userDir,"jvm-",".args");
                var arguments=List.of(AppDefaults.Launcher.WORKER_MAX_HEAP,"-cp",absoluteClasspath(),"com.matsim.viz.Main","--launch-settings",session.toString());
                Files.write(javaArgs,arguments.stream().map(PortableLauncher::quoteJavaArgument).toList(),java.nio.charset.Charset.defaultCharset());
                var command=List.of(javaExecutable.toString(),"@"+javaArgs);
                process=new ProcessBuilder(command).directory(userDir.toFile()).redirectErrorStream(true).start();
                SwingUtilities.invokeLater(()->status.setToolTipText("Full startup log: " + log));
                // Drain output continuously on this worker, retaining the full log on disk.
                try (var reader = process.inputReader(); var writer = Files.newBufferedWriter(log)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.write(line);writer.newLine();writer.flush();
                        publish(line);
                    }
                }
                return process.waitFor();
            }
            protected void process(List<String> lines) {
                for (String line : lines) {
                    startupLog.append(line + "\n");
                    if (line.startsWith("[Startup] ")) {
                        status.setText(line.substring("[Startup] ".length()));
                        if (line.equals("[Startup] Viewer ready")) progress.setIndeterminate(false);
                    }
                }
                int excess = startupLog.getDocument().getLength() - AppDefaults.Launcher.LOG_MAX_CHARACTERS;
                if (excess > 0) startupLog.replaceRange("", 0, excess);
                startupLog.setCaretPosition(startupLog.getDocument().getLength());
            }
            protected void done(){progress.setIndeterminate(false);window.remove(loadingPanel);window.add(formScroll,BorderLayout.CENTER);window.revalidate();window.repaint();loading=false;setEnabled(form,true);updateMode();try{int exit=get();if(exit!=0)throw new IllegalStateException("Application exited with code "+exit+". Log: "+log);status.setText("Visualization closed. Choose another simulation or run again.");}catch(Exception ex){JOptionPane.showMessageDialog(window,cause(ex).getMessage(),"Could not start visualization",JOptionPane.ERROR_MESSAGE);status.setText("Startup failed. Check paths and try again.");}finally{process=null;for(Path temporary:new Path[]{session,javaArgs})if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(Exception ignored){}refreshCaches();window.setState(Frame.NORMAL);}}
        }.execute();
    }
    private static Throwable cause(Exception ex){return ex.getCause()==null?ex:ex.getCause();}
    private static void setEnabled(Container parent,boolean value){for(Component c:parent.getComponents()){c.setEnabled(value);if(c instanceof Container child)setEnabled(child,value);}}
    private static String quoteJavaArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
    private static String absoluteClasspath() {
        try {
            Path own = Path.of(PortableLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // The packaged JAR manifest references lib/*.jar. Avoid a huge Windows command line.
            if (Files.isRegularFile(own) && own.toString().endsWith(".jar")) return own.toString();
        } catch (java.net.URISyntaxException ex) { throw new IllegalStateException("Invalid application path", ex); }
        var entries = new LinkedHashSet<String>();
        // Maven exec:java keeps project dependencies in its context loader, not java.class.path.
        for (ClassLoader loader = PortableLauncher.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) for (var url : urls.getURLs()) {
                if ("file".equals(url.getProtocol())) try { entries.add(Path.of(url.toURI()).toAbsolutePath().toString()); }
                catch (java.net.URISyntaxException ex) { throw new IllegalStateException("Invalid classpath URL", ex); }
            }
        }
        if (entries.isEmpty()) Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(s -> Path.of(s).toAbsolutePath().toString()).forEach(entries::add);
        return String.join(java.io.File.pathSeparator, entries);
    }
}
