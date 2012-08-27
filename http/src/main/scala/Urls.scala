
// nonsense dsl stuff for making urls

package httpbridge

  case class Url(path: String) {

    def /(seg: String): Url = {
      if(path.last == '/')
        Url(path + seg)
      else
        Url(path + "/" + seg)
    }

  }

trait Urls {

  def :/(root: String): Url = Url(root)

  implicit def urlToStr(url: Url): String = url.path

}