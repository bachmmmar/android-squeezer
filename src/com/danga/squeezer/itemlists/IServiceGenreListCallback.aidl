package com.danga.squeezer.itemlists;
import com.danga.squeezer.model.SqueezerGenre;

oneway interface IServiceGenreListCallback {
  void onGenresReceived(int count, int max, int pos, in List<SqueezerGenre> albums);
}

