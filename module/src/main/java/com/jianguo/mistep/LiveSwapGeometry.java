package com.jianguo.mistep;

/** Geometry for two real task surfaces. No bitmap, View, or task-bounds interpolation. */
final class LiveSwapGeometry {
    private LiveSwapGeometry() {}

    static final class Affine {
        final double a, b, c, d, x, y;
        Affine(double a, double b, double c, double d, double x, double y) {
            double[] values = {a, b, c, d, x, y};
            for (double value : values)
                if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite transform");
            this.a = a; this.b = b; this.c = c; this.d = d; this.x = x; this.y = y;
        }
        static Affine identity() { return new Affine(1, 0, 0, 1, 0, 0); }
        Affine inverse() {
            double determinant = a * d - b * c;
            if (!Double.isFinite(determinant) || Math.abs(determinant) < 1e-8)
                throw new IllegalArgumentException("singular parent transform");
            return new Affine(d / determinant, -b / determinant, -c / determinant,
                    a / determinant, (c * y - d * x) / determinant,
                    (b * x - a * y) / determinant);
        }
        /** This transform after child: this(child(point)). */
        Affine times(Affine child) {
            return new Affine(a * child.a + c * child.b, b * child.a + d * child.b,
                    a * child.c + c * child.d, b * child.c + d * child.d,
                    a * child.x + c * child.y + x, b * child.x + d * child.y + y);
        }
        double mapX(double px, double py) { return a * px + c * py + x; }
        double mapY(double px, double py) { return b * px + d * py + y; }
    }

    static final class Rect {
        final double left, top, width, height;
        Rect(double left, double top, double width, double height) {
            if (!Double.isFinite(left) || !Double.isFinite(top)
                    || !Double.isFinite(width) || !Double.isFinite(height)
                    || width <= 0 || height <= 0)
                throw new IllegalArgumentException("invalid physical rectangle");
            this.left = left; this.top = top; this.width = width; this.height = height;
        }
        Rect interpolate(Rect end, double fraction) {
            if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1)
                throw new IllegalArgumentException("invalid animation fraction");
            return new Rect(mix(left, end.left, fraction), mix(top, end.top, fraction),
                    mix(width, end.width, fraction), mix(height, end.height, fraction));
        }
    }

    static final class Task {
        final int id, bufferWidth, bufferHeight;
        final Object token;
        final Rect start, end;
        Task(int id, Object token, int bufferWidth, int bufferHeight, Rect start, Rect end) {
            if (id < 0 || token == null || bufferWidth <= 0 || bufferHeight <= 0
                    || start == null || end == null)
                throw new IllegalArgumentException("invalid task geometry");
            this.id = id; this.token = token; this.bufferWidth = bufferWidth;
            this.bufferHeight = bufferHeight; this.start = start; this.end = end;
        }
    }

    static final class Frame {
        final Task task;
        final Rect physical;
        final Affine local, physicalFromBuffer, bufferFromPhysical, contentFromBuffer;
        Frame(Task task, Rect physical, Affine parentToPhysical, Affine contentFromBuffer) {
            this.task = task; this.physical = physical;
            this.contentFromBuffer = contentFromBuffer;
            physicalFromBuffer = new Affine(physical.width / task.bufferWidth, 0, 0,
                    physical.height / task.bufferHeight, physical.left, physical.top);
            local = parentToPhysical.inverse().times(physicalFromBuffer).times(contentFromBuffer.inverse());
            bufferFromPhysical = physicalFromBuffer.inverse();
        }
    }

    static Frame[] frames(Task first, Task second, Affine parentToPhysical, double fraction) {
        return frames(first, second, parentToPhysical, Affine.identity(), Affine.identity(), fraction);
    }

    static Frame[] frames(Task first, Task second, Affine parentToPhysical,
            Affine firstContentFromBuffer, Affine secondContentFromBuffer, double fraction) {
        if (first.id == second.id || first.token.equals(second.token))
            throw new IllegalArgumentException("two different tasks required");
        if (parentToPhysical == null) throw new IllegalArgumentException("parent transform not verified");
        if (firstContentFromBuffer == null || secondContentFromBuffer == null)
            throw new IllegalArgumentException("task content transform not verified");
        return new Frame[]{new Frame(first, first.start.interpolate(first.end, fraction), parentToPhysical, firstContentFromBuffer),
                new Frame(second, second.start.interpolate(second.end, fraction), parentToPhysical, secondContentFromBuffer)};
    }

    static Frame[] frames(Task[] tasks, Affine parentToPhysical, Affine[] content, double fraction) {
        if (tasks == null || content == null || tasks.length == 0 || tasks.length != content.length
                || parentToPhysical == null)
            throw new IllegalArgumentException("unverified live task set");
        Frame[] frames = new Frame[tasks.length];
        for (int i = 0; i < tasks.length; i++) {
            if (tasks[i] == null || content[i] == null)
                throw new IllegalArgumentException("missing live task/content");
            for (int j = 0; j < i; j++)
                if (tasks[i].id == tasks[j].id || tasks[i].token.equals(tasks[j].token))
                    throw new IllegalArgumentException("duplicate live task");
            frames[i] = new Frame(tasks[i], tasks[i].start.interpolate(tasks[i].end, fraction),
                    parentToPhysical, content[i]);
        }
        return frames;
    }

    private static double mix(double start, double end, double fraction) {
        return start + (end - start) * fraction;
    }
}
