package bayou.html;

import java.util.AbstractList;

class HtmlChildList extends AbstractList<HtmlPiece>
{
    final HtmlParent parent;

    HtmlChildList(HtmlParent parent)
    {
        this.parent = parent;
    }

    @Override
    public HtmlPiece get(int index)
    {
        return parent.getChild(index);
    }

    @Override
    public int size()
    {
        return parent.getChildCount();
    }

    @Override
    public HtmlPiece set(int index, HtmlPiece element)
    {
        return parent.setChild(index, element);
    }

    @Override
    public void add(int index, HtmlPiece element)
    {
        parent.addChild(index, element);
    }

    @Override
    public HtmlPiece remove(int index)
    {
        return parent.removeChild(index);
    }
}
