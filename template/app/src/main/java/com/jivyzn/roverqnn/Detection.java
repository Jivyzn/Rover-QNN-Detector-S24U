package com.jivyzn.roverqnn;

final class Detection {
    final float left;
    final float top;
    final float right;
    final float bottom;
    final float confidence;
    final int classId;
    final String label;

    Detection(float left, float top, float right, float bottom,
              float confidence, int classId, String label) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.confidence = confidence;
        this.classId = classId;
        this.label = label;
    }
}
