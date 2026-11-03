package com.cama;

import com.cama.ui.CameraFrame;
import com.cama.ui.Theme;

import javax.swing.SwingUtilities;

public class Main {

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        System.setProperty("sun.java2d.noddraw", "true");

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Theme.install();
                new CameraFrame().setVisible(true);
            }
        });
    }
}
