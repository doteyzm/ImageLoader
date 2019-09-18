package com.test.imageloader;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

import com.test.imageloader.utils.ImageLoader;

public class MainActivity extends AppCompatActivity {
    private String url = "http://photocdn.sohu.com/20130925/Img387224863.jpg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ImageView imageView = findViewById(R.id.img);
        ImageLoader imageLoader = new ImageLoader(this);
        imageLoader.bindBitmap(url, imageView, 0, 0);
    }
}
