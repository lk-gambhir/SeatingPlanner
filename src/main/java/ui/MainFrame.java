package ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;

/** Initial Swing shell; feature dashboards are added in the planned implementation order. */
public final class MainFrame extends JFrame {
    public MainFrame(Path databasePath) {
        super("Thapar Exam Seating and Invigilation Planner");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        setLocationByPlatform(true);

        JLabel heading = new JLabel("Exam operations", SwingConstants.LEADING);
        heading.setBorder(BorderFactory.createEmptyBorder(18, 20, 10, 20));
        heading.setFont(heading.getFont().deriveFont(22f));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Invigilators", placeholder("Invigilator management will be built first."));
        tabs.addTab("Rooms", placeholder("Room inventory and seat layouts will be managed here."));
        tabs.addTab("Students", placeholder("Student and course records will be managed here."));
        tabs.addTab("Exam planning", placeholder("Date-sheet intake and plan generation will follow."));

        JLabel status = new JLabel("Local database: " + databasePath);
        status.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JPanel content = new JPanel(new BorderLayout());
        content.add(heading, BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);
    }

    private static JPanel placeholder(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(24, 20, 24, 20));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }
}
