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
    private final Map<String,JTextField> inputs = new LinkedHashMap<>();
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"Simulation files - reuse or build cache", "Existing cache - no source files required"});
    private final JComboBox<Path> entries = new JComboBox<>();
    private final JLabel status = new JLabel("Choose a config to discover simulation files.");
    private final JButton run = new JButton("Run visualization"), scan = new JButton("Rescan config");
    private final JPanel form = new FormPanel();
    private final Path userDir;
    private final Properties base;
    private final javax.swing.Timer configTimer, cacheTimer;
    private long generation;
    private boolean loading;
    private volatile Process process;
    private int row;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { new PortableLauncher().show(); }
            catch (Exception ex) { ex.printStackTrace(); JOptionPane.showMessageDialog(null,ex.toString(),"Startup failed",JOptionPane.ERROR_MESSAGE); }
        });
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
        String[][] fields={{"network","Network *"},{"events","Events *"},{"population","Population (optional)"},{"trips","Trips CSV"},{"persons","Persons CSV"},{"plans","Output plans"},{"schedule","Transit schedule"},{"vehicles","Transit vehicles"}};
        for(String[] field:fields){var text=new JTextField(base.getProperty("input."+field[0],""));inputs.put(field[0],text);addRow(field[1],text,browse(text,false));}
        addRow("Cache folder",cache,browse(cache,true));
        addRow("Existing cache",entries,null);
        entries.setRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean selected,boolean focus){return super.getListCellRendererComponent(l,value instanceof Path p?p.getFileName():value,index,selected,focus);}});
        JLabel help=new JLabel("<html>* Network and events are required in file mode. Optional files may be cleared.<br>Cache-only mode includes movements, network and cached metadata; editing PT schedules needs the source files.</html>");
        JPanel bottom=new JPanel(new BorderLayout(8,8));bottom.add(help,BorderLayout.NORTH);bottom.add(status,BorderLayout.CENTER);bottom.add(run,BorderLayout.SOUTH);
        bottom.setBorder(BorderFactory.createEmptyBorder(10,12,12,12));
        window.add(new JScrollPane(form),BorderLayout.CENTER);window.add(bottom,BorderLayout.SOUTH);
        Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setSize(Math.min(AppDefaults.Launcher.WIDTH_PIXELS,(int)(screen.width*AppDefaults.Launcher.SCREEN_FRACTION)),Math.min(AppDefaults.Launcher.HEIGHT_PIXELS,(int)(screen.height*AppDefaults.Launcher.SCREEN_FRACTION)));
        window.setLocationRelativeTo(null);window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        window.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){if(loading || (process!=null&&process.isAlive())){window.setState(Frame.ICONIFIED);}else window.dispose();}});
        configTimer=new javax.swing.Timer(AppDefaults.Launcher.SCAN_DELAY_MS,e->scan());configTimer.setRepeats(false);
        cacheTimer=new javax.swing.Timer(AppDefaults.Launcher.SCAN_DELAY_MS,e->refreshCaches());cacheTimer.setRepeats(false);
        changed(config,()->{generation++;if(mode.getSelectedIndex()==0){run.setEnabled(false);configTimer.restart();}});
        changed(cache,cacheTimer::restart);
        scan.addActionListener(e->scan());run.addActionListener(e->run());mode.addActionListener(e->updateMode());
        mode.setSelectedIndex("cache".equals(base.getProperty("launch.mode",AppDefaults.Launcher.MODE))?1:0);
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
    private JButton browse(JTextField target,boolean directory){var b=new JButton("Browse...");b.addActionListener(e->{var chooser=new JFileChooser();chooser.setFileSelectionMode(directory?JFileChooser.DIRECTORIES_ONLY:JFileChooser.FILES_ONLY);if(!target.getText().isBlank())chooser.setSelectedFile(new java.io.File(target.getText()));if(chooser.showOpenDialog(window)==JFileChooser.APPROVE_OPTION)target.setText(chooser.getSelectedFile().getAbsolutePath());});return b;}
    private static void changed(JTextField field,Runnable action){field.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){action.run();}public void removeUpdate(DocumentEvent e){action.run();}public void changedUpdate(DocumentEvent e){action.run();}});}
    private void updateMode(){boolean files=mode.getSelectedIndex()==0;config.setEnabled(files);scan.setEnabled(files);inputs.values().forEach(t->t.setEnabled(files));entries.setEnabled(!files);run.setEnabled(!loading);}
    private void scan(){
        if(loading||mode.getSelectedIndex()!=0)return;
        long request=++generation;String path=config.getText().trim();
        inputs.values().forEach(t->t.setText(""));run.setEnabled(false);status.setText("Scanning config...");
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
    private Properties selection(){Properties p=new Properties();p.putAll(base);p.setProperty("matsim.config.file",config.getText().trim());p.setProperty("cache.dir",cache.getText().trim());p.setProperty("launch.mode",mode.getSelectedIndex()==1?"cache":"files");inputs.forEach((key,text)->p.setProperty("input."+key,text.getText().trim()));p.setProperty("launch.cache.file",entries.getSelectedItem()==null?"":entries.getSelectedItem().toString());return p;}
    private void run(){
        Properties settings=selection();loading=true;configTimer.stop();generation++;setEnabled(form,false);run.setEnabled(false);status.setText("Loading simulation... Startup details are written to the log.");
        new SwingWorker<Integer,Void>(){
            Path log,session;
            protected Integer doInBackground()throws Exception{
                LaunchSettings.resolve(settings);
                LaunchSettings.write(userDir.resolve("launcher.properties"),settings);
                session=Files.createTempFile(userDir,"launch-",".properties");LaunchSettings.write(session,settings);
                log=Files.createTempFile(userDir,"startup-",".log");
                Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java");
                var command=List.of(java.toString(),AppDefaults.Launcher.WORKER_MAX_HEAP,"-cp",absoluteClasspath(),"com.matsim.viz.Main","--launch-settings",session.toString());
                process=new ProcessBuilder(command).directory(userDir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                SwingUtilities.invokeLater(()->{status.setText("Visualization running/loading. Log: "+log);status.setToolTipText(log.toString());});
                return process.waitFor();
            }
            protected void done(){loading=false;setEnabled(form,true);updateMode();try{int exit=get();if(exit!=0)throw new IllegalStateException("Application exited with code "+exit+". Log: "+log);status.setText("Visualization closed. Choose another simulation or run again.");}catch(Exception ex){JOptionPane.showMessageDialog(window,cause(ex).getMessage(),"Could not start visualization",JOptionPane.ERROR_MESSAGE);status.setText("Startup failed. Check paths and try again.");}finally{process=null;if(session!=null)try{Files.deleteIfExists(session);}catch(Exception ignored){}refreshCaches();window.setState(Frame.NORMAL);}}
        }.execute();
    }
    private static Throwable cause(Exception ex){return ex.getCause()==null?ex:ex.getCause();}
    private static void setEnabled(Container parent,boolean value){for(Component c:parent.getComponents()){c.setEnabled(value);if(c instanceof Container child)setEnabled(child,value);}}
    private static String absoluteClasspath() {
        try {
            Path own = Path.of(PortableLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // The packaged JAR manifest references lib/*.jar. Avoid a huge Windows command line.
            if (Files.isRegularFile(own) && own.toString().endsWith(".jar")) return own.toString();
        } catch (java.net.URISyntaxException ex) { throw new IllegalStateException("Invalid application path", ex); }
        return Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(s -> Path.of(s).toAbsolutePath().toString()).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }
}
