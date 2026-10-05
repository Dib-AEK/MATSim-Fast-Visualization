package com.matsim.viz.ui;

import com.matsim.viz.Main;
import com.matsim.viz.launcher.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Desktop smoke test; pass a synthetic network-only.properties from LauncherCheck. */
public final class MainLauncherUiCheck {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory(Path.of("target").toAbsolutePath(),"main-launcher-");
        var settings=LaunchSettings.read(Path.of(args[0]));LaunchSettings.write(home.resolve("launcher.properties"),settings);
        System.setProperty("matsim.viz.userDir",home.toString());
        var failure=new AtomicReference<Throwable>();var started=new AtomicBoolean();var passed=new AtomicBoolean();
        var executor=Executors.newScheduledThreadPool(2);
        executor.schedule(()->{failure.set(new AssertionError("Startup window timed out"));SwingUtilities.invokeLater(()->{for(Window w:Window.getWindows())w.dispose();});},45,TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(()->{
            JFrame[] frame={null};
            try {
                SwingUtilities.invokeAndWait(()->{for(Window w:Window.getWindows())if(w instanceof JFrame f&&f.isShowing()&&f.getTitle().startsWith("MATSim Fast Visualization"))frame[0]=f;});
                if(frame[0]==null||!started.compareAndSet(false,true))return;
                var cp=PortableLauncher.class.getDeclaredMethod("absoluteClasspath");cp.setAccessible(true);
                var quote=PortableLauncher.class.getDeclaredMethod("quoteJavaArgument",String.class);quote.setAccessible(true);
                var command=java.util.List.of("-cp",(String)cp.invoke(null),"com.matsim.viz.Main","--launch-settings",Path.of(args[0]).toAbsolutePath().toString(),"--build-cache");
                var lines=new ArrayList<String>();for(String value:command)lines.add((String)quote.invoke(null,value));
                Path argfile=home.resolve("worker.args");Files.write(argfile,lines,java.nio.charset.Charset.defaultCharset());
                String binary=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";
                Path log=home.resolve("worker.log");
                Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",binary).toString(),"@"+argfile).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    if(!child.waitFor(30,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Worker timed out");}
                    String output=Files.readString(log);
                    if(child.exitValue()!=0||!output.contains("Network loaded; network-only mode"))throw new AssertionError("Worker failed: "+output);
                    passed.set(true);
                } finally {if(child.isAlive())child.destroyForcibly();}
            }catch(Throwable ex){failure.set(ex);}finally{if(frame[0]!=null&&started.get())SwingUtilities.invokeLater(frame[0]::dispose);}
        },1,1,TimeUnit.SECONDS);
        try{Main.main(new String[0]);if(failure.get()!=null)throw new AssertionError(failure.get());if(!passed.get())throw new AssertionError("No startup check completed");}
        finally{executor.shutdownNow();}
        System.out.println("PASS: Main opens startup window and waits; child JVM works with development/Maven classpath and argument file; explicit processing bypasses launcher.");
    }
}
