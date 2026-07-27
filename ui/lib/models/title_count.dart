class TitleCount {
  final String title;
  final int count;

  TitleCount({required this.title, required this.count});

  factory TitleCount.fromMap(Map<dynamic, dynamic> map) {
    return TitleCount(
      title: map['title'] as String,
      count: map['count'] as int,
    );
  }

  Map<String, dynamic> toMap() {
    return {'title': title, 'count': count};
  }
}
