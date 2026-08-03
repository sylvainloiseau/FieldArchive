package fr.cnrs.lacito.fieldarchive.dtos;

public class CardinalityDto {
    private Integer min; // null = 0
    private Integer max; // null = illimité

    public CardinalityDto() {}
    public CardinalityDto(Integer min, Integer max) { this.min = min; this.max = max; }

    public Integer getMin() { return min; }
    public void setMin(Integer min) { this.min = min; }
    public Integer getMax() { return max; }
    public void setMax(Integer max) { this.max = max; }

    @Override
    public String toString() {
        String mi = min == null ? "0" : String.valueOf(min);
        String ma = max == null ? "*" : String.valueOf(max);
        return mi + ".." + ma;
    }
}